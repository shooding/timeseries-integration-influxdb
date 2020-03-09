/*******************************************************************************
 * This file is part of OpenNMS(R).
 *
 * Copyright (C) 2006-2020 The OpenNMS Group, Inc.
 * OpenNMS(R) is Copyright (C) 1999-2020 The OpenNMS Group, Inc.
 *
 * OpenNMS(R) is a registered trademark of The OpenNMS Group, Inc.
 *
 * OpenNMS(R) is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License,
 * or (at your option) any later version.
 *
 * OpenNMS(R) is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with OpenNMS(R).  If not, see:
 *      http://www.gnu.org/licenses/
 *
 * For more information contact:
 *     OpenNMS(R) Licensing <license@opennms.org>
 *     http://www.opennms.org/
 *     http://www.opennms.com/
 *******************************************************************************/

package org.opennms.timeseries.impl.influxdb;

import static org.opennms.timeseries.impl.influxdb.TransformUtil.metricKeyToInflux;
import static org.opennms.timeseries.impl.influxdb.TransformUtil.tagValueFromInflux;
import static org.opennms.timeseries.impl.influxdb.TransformUtil.tagValueToInflux;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.opennms.integration.api.v1.timeseries.Metric;
import org.opennms.integration.api.v1.timeseries.Sample;
import org.opennms.integration.api.v1.timeseries.StorageException;
import org.opennms.integration.api.v1.timeseries.Tag;
import org.opennms.integration.api.v1.timeseries.TimeSeriesFetchRequest;
import org.opennms.integration.api.v1.timeseries.TimeSeriesStorage;
import org.opennms.integration.api.v1.timeseries.immutables.ImmutableMetric;
import org.opennms.integration.api.v1.timeseries.immutables.ImmutableSample;
import org.opennms.integration.api.v1.timeseries.immutables.ImmutableTag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.influxdb.client.DeleteApi;
import com.influxdb.client.InfluxDBClient;
import com.influxdb.client.InfluxDBClientFactory;
import com.influxdb.client.InfluxDBClientOptions;
import com.influxdb.client.QueryApi;
import com.influxdb.client.WriteApi;
import com.influxdb.client.domain.DeletePredicateRequest;
import com.influxdb.client.domain.WritePrecision;
import com.influxdb.client.write.Point;
import com.influxdb.query.FluxRecord;
import com.influxdb.query.FluxTable;

/**
 * Implementation of TimeSeriesStorage that uses InfluxdbStorage.
 *
 * Design choices:
 * - we fill the _measurement column with the Metrics key
 * - we prefix the tag key with the tag type ('intrinsic' or 'meta')
 */
public class InfluxdbStorage implements TimeSeriesStorage {
    private static final Logger LOG = LoggerFactory.getLogger(InfluxdbStorage.class);

    private final InfluxDBClient influxDBClient;
    private final WriteApi writeApi;
    private final QueryApi queryApi;
    private final DeleteApi deleteApi;

    private final String configBucket;
    private final String configOrg;

    private LoadingCache<String, List<Metric>> cache = CacheBuilder.newBuilder()
            .maximumSize(1000)
            .expireAfterWrite(60, TimeUnit.SECONDS)
            .build(
                    new CacheLoader<String, List<Metric>>() {
                        public List<Metric> load(final String ignored) throws StorageException {
                            return loadAllMetrics();
                        }
                    });

    /** Uses default values for bucket, org, url. */
    public InfluxdbStorage(final String token) {
        this("opennms", "opennms", token, "http://localhost:9999");
    }

    public InfluxdbStorage(
            String bucket,
            String org,
            String token,
            String url) {
        this.configBucket = Objects.requireNonNull(bucket, "Parameter influxdbBucket cannot be null");
        this.configOrg = Objects.requireNonNull(org, "Parameter influxdbOrg cannot be null");
        Objects.requireNonNull(org, "Parameter influxdbToken cannot be null");
        Objects.requireNonNull(org, "Parameter influxdbUrl cannot be null");

        InfluxDBClientOptions options = InfluxDBClientOptions.builder()
                .bucket(configBucket)
                .org(configOrg)
                .url(url)
                .authenticateToken(token.toCharArray())
                .build();
        influxDBClient = InfluxDBClientFactory.create(options);
        // TODO Patrick: do we need to enable batch? How?

        // Fetch the APIs once during init, some of these require to be closed
        queryApi = influxDBClient.getQueryApi();
        writeApi = influxDBClient.getWriteApi();
        deleteApi = influxDBClient.getDeleteApi();

        LOG.info("Successfully initialized InfluxDB client.");
    }

    public void destroy() {
        writeApi.close();
        influxDBClient.close();
    }

    @Override
    public void store(List<Sample> samples) {
        for(Sample sample: samples) {
            Point point = Point
                    .measurement(metricKeyToInflux(sample.getMetric().getKey())) // make sure the measurement has only allowed characters
                    .addField("value", sample.getValue())
                    .time(sample.getTime().toEpochMilli(), WritePrecision.MS);
            storeTags(point, ImmutableMetric.TagType.intrinsic, sample.getMetric().getTags());
            storeTags(point, ImmutableMetric.TagType.meta, sample.getMetric().getMetaTags());
            writeApi.writePoint(configBucket, configOrg, point);
        }
    }

    private void storeTags(final Point point, final ImmutableMetric.TagType tagType, final Collection<Tag> tags) {
        for(final Tag tag : tags) {
            String value = tag.getValue();
            value = tagValueToInflux(value); // Influx has a problem with a colon in a tag value if we query for it
            point.addTag(toClassifiedTagKey(tagType, tag), value);
        }
    }

    private String toClassifiedTagKey(final ImmutableMetric.TagType tagType, final Tag tag) {
         return tagType.name() + "_" + tag.getKey();
    }

    @Override
    public List<Metric> getMetrics(Collection<Tag> tags) throws StorageException {
        try {
            return this.cache.get("allMetrics")
                    .stream()
                    .filter(metric -> containsAll(metric, tags))
                    .collect(Collectors.toList());
        } catch (ExecutionException e) {
            throw new StorageException(e);
        }
    }

    private List<Metric> loadAllMetrics() throws StorageException {
        // TODO: Patrick: The code works but is probably not efficient enough, we should optimize this query since
        // it gets way too much (redundant) data.
        // I am not sure how - the influx documentation doesn't seem to be up to date / correct:
        // https://www.influxdata.com/blog/schema-queries-in-ifql/

        final String query = "from(bucket:\"opennms\")\n" +
                "  |> range(start:-5y)\n" +
                "  |> keys()";

        return queryApi
                .query(query)
                .stream()
                .map(FluxTable::getRecords)
                .flatMap(Collection::stream)
                .map(FluxRecord::getValues)
                .filter(m -> m.get("_measurement") != null && m.get("_measurement").toString().contains("resourceId"))
                .map(this::createMetricFromMap)
                .distinct()
                .collect(Collectors.toList());
    }

        /** Restore the metric from the tags we get out of InfluxDb */
    private Metric createMetricFromMap(final Map<String, Object> map) {
        ImmutableMetric.MetricBuilder metric = ImmutableMetric.builder();
        for(Map.Entry<String, Object> entry : map.entrySet()) {
            getIfMatching(ImmutableMetric.TagType.intrinsic, entry).ifPresent(metric::tag);
            getIfMatching(ImmutableMetric.TagType.meta, entry).ifPresent(metric::metaTag);
        }
        return metric.build();
    }

    private Optional<Tag> getIfMatching(final ImmutableMetric.TagType tagType, final Map.Entry<String, Object> entry) {
        // Check if the key starts with the prefix. If so it is an opennms key, if not something InfluxDb specific and
        // we can ignore it.
        final String prefix = tagType.name() + '_';
        String key = entry.getKey();

        if(key.startsWith(prefix)) {
            key = key.substring(prefix.length());
            String value = tagValueFromInflux(entry.getValue().toString()); // convert
            return Optional.of(new ImmutableTag(key, value));
        }
        return Optional.empty();
    }

    private boolean containsAll(final Metric metric, final Collection<Tag> tags) {
        for(Tag tag: tags) {
            if(!metric.getTags().contains(tag) && !metric.getMetaTags().contains(tag)){
                return false;
            }
        }
        return true;
    }

    @Override
    public List<Sample> getTimeseries(TimeSeriesFetchRequest request) throws StorageException {
        DateTimeFormatter format = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneId.of("UTC"));

        long stepInSeconds = request.getStep().getSeconds();
        String query = "from(bucket:\"" + this.configBucket + "\")\n" +
                " |> range(start:" + format.format(request.getStart()) + ", stop:" + format.format(request.getEnd()) + ")\n" +
                " |> filter(fn:(r) => r._measurement == \"" + metricKeyToInflux(request.getMetric().getKey()) + "\")\n" +
                " |> filter(fn: (r) => r._field == \"value\")";
               // " |> aggregateWindow(every: " + stepInSeconds +"s,fn: mean)"; // TODO: Patrick: this crashes the whole Indluxdb server
        List<FluxTable> tables = queryApi.query(query);

        final List<Sample> samples = new ArrayList<>();
        for (FluxTable fluxTable : tables) {
            List<FluxRecord> records = fluxTable.getRecords();
            for (FluxRecord record : records) {
                Sample sample = ImmutableSample.builder()
                        .metric(request.getMetric())
                        .time(record.getTime())
                        .value((Double)record.getValue())
                        .build();
                samples.add(sample);
            }
        }
        return samples;
    }

    @Override
    public void delete(Metric metric) throws StorageException {
        DeletePredicateRequest predicate = new DeletePredicateRequest().predicate("_measurement=\"" + metricKeyToInflux(metric.getKey()) + "\"");
        deleteApi.delete(predicate, configBucket, configOrg);
    }
}
