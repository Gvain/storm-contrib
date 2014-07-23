package storm.kafka;

import backtype.storm.Config;
import backtype.storm.metric.api.*;
import backtype.storm.spout.SpoutOutputCollector;
import backtype.storm.utils.Utils;
import com.google.common.collect.ImmutableMap;
import java.util.*;
import kafka.api.FetchRequest;
import kafka.api.OffsetRequest;
import kafka.javaapi.consumer.SimpleConsumer;
import kafka.javaapi.message.ByteBufferMessageSet;
import kafka.message.MessageAndOffset;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import storm.kafka.KafkaSpout.EmitState;
import storm.kafka.KafkaSpout.MessageAndRealOffset;
import storm.kafka.trident.MaxMetric;

public class PartitionManager {
    public static final Logger LOG = LoggerFactory.getLogger(PartitionManager.class);
    private final CombinedMetric _fetchAPILatencyMax;
    private final ReducedMetric _fetchAPILatencyMean;
    private final CountMetric _fetchAPICallCount;
    private final CountMetric _fetchAPIMessageCount;

    static class KafkaMessageId {
        public GlobalPartitionId partition;
        public long offset;

        public KafkaMessageId(GlobalPartitionId partition, long offset) {
            this.partition = partition;
            this.offset = offset;
        }
    }

    Long _emittedToOffset;
    SortedSet<Long> _pending = new TreeSet<Long>();
    Long _committedTo;
    LinkedList<MessageAndRealOffset> _waitingToEmit = new LinkedList<MessageAndRealOffset>();
    GlobalPartitionId _partition;
    SpoutConfig _spoutConfig;
    String _topologyInstanceId;
    SimpleConsumer _consumer;
    DynamicPartitionConnections _connections;
    ZkState _state;
    Map _stormConf;
    int _sampleAckCount;


    public PartitionManager(DynamicPartitionConnections connections, String topologyInstanceId, ZkState state, Map stormConf, SpoutConfig spoutConfig, GlobalPartitionId id) {
        _partition = id;
        _connections = connections;
        _spoutConfig = spoutConfig;
        _topologyInstanceId = topologyInstanceId;
        _consumer = connections.register(id.host, id.partition);
	_state = state;
        _stormConf = stormConf;

        String jsonTopologyId = null;
        Long jsonOffset = null;
        try {
            Map<Object, Object> json = _state.readJSON(committedPath());
            if(json != null) {
                jsonTopologyId = (String)((Map<Object,Object>)json.get("topology")).get("id");
                jsonOffset = (Long)json.get("offset");
            }
        }
        catch(Throwable e) {
            LOG.warn("Error reading and/or parsing at ZkNode: " + committedPath(), e);
        }

        if(!topologyInstanceId.equals(jsonTopologyId) && spoutConfig.forceFromStart) {
            _committedTo = _consumer.getOffsetsBefore(spoutConfig.topic, id.partition, spoutConfig.startOffsetTime, 1)[0];
	    LOG.info("Using startOffsetTime to choose last commit offset.");
        } else if(jsonTopologyId == null || jsonOffset == null) { // failed to parse JSON?
            _committedTo = _consumer.getOffsetsBefore(spoutConfig.topic, id.partition, -1, 1)[0];
	    LOG.info("Setting last commit offset to HEAD.");
        } else {
            _committedTo = jsonOffset;
	    LOG.info("Read last commit offset from zookeeper: " + _committedTo);
        }

        LOG.info("Starting Kafka " + _consumer.host() + ":" + id.partition + " from offset " + _committedTo);
        _emittedToOffset = _committedTo;
        _sampleAckCount = 0;

        _fetchAPILatencyMax = new CombinedMetric(new MaxMetric());
        _fetchAPILatencyMean = new ReducedMetric(new MeanReducer());
        _fetchAPICallCount = new CountMetric();
        _fetchAPIMessageCount = new CountMetric();
    }

    public Map getMetricsDataMap() {
        Map ret = new HashMap();
        ret.put(_partition + "/fetchAPILatencyMax", _fetchAPILatencyMax.getValueAndReset());
        ret.put(_partition + "/fetchAPILatencyMean", _fetchAPILatencyMean.getValueAndReset());
        ret.put(_partition + "/fetchAPICallCount", _fetchAPICallCount.getValueAndReset());
        ret.put(_partition + "/fetchAPIMessageCount", _fetchAPIMessageCount.getValueAndReset());
        return ret;
    }

    //returns false if it's reached the end of current batch
    public EmitState next(SpoutOutputCollector collector) {
        if(_waitingToEmit.isEmpty()) fill();
        while(true) {
            MessageAndRealOffset toEmit = _waitingToEmit.pollFirst();
            if(toEmit==null) {
                return EmitState.NO_EMITTED;
            }
            Iterable<List<Object>> tups = _spoutConfig.scheme.deserialize(Utils.toByteArray(toEmit.msg.payload()));
            if(tups!=null) {
                if (toEmit.offset < 0) {
                    for(List<Object> tup: tups)
                        collector.emit(tup);
                } else {
                    for(List<Object> tup: tups)
                        collector.emit(tup, new KafkaMessageId(_partition, toEmit.offset));
                }
                break;
            } else if (toEmit.offset > 0) {
                ack(toEmit.offset);
            }
        }
        if(!_waitingToEmit.isEmpty()) {
            return EmitState.EMITTED_MORE_LEFT;
        } else {
            return EmitState.EMITTED_END;
        }
    }

    private void fill() {
        //LOG.info("Fetching from Kafka: " + _consumer.host() + ":" + _partition.partition + " from offset " + _emittedToOffset);
        long start = System.nanoTime();
        ByteBufferMessageSet msgs = _consumer.fetch(
                new FetchRequest(
                    _spoutConfig.topic,
                    _partition.partition,
                    _emittedToOffset,
                    _spoutConfig.fetchSizeBytes));
        long end = System.nanoTime();
        long millis = (end - start) / 1000000;
        _fetchAPILatencyMax.update(millis);
        _fetchAPILatencyMean.update(millis);
        _fetchAPICallCount.incr();
        _fetchAPIMessageCount.incrBy(msgs.underlying().size());

        int numMessages = msgs.underlying().size();
        if(numMessages>0) {
          LOG.info("Fetched " + numMessages + " messages from Kafka: " + _consumer.host() + ":" + _partition.partition);
        }
        for(MessageAndOffset msg: msgs) {
            long offset = -1;
            if (_sampleAckCount++ % _spoutConfig.sampleAckRate == 0) {
                _pending.add(_emittedToOffset);
                offset = _emittedToOffset;
                _emittedToOffset = msg.offset();
                _sampleAckCount = 1;
            }
            _waitingToEmit.add(new MessageAndRealOffset(msg.message(), offset));
        }
        if(numMessages>0) {
          LOG.info("Added " + numMessages + " messages from Kafka: " + _consumer.host() + ":" + _partition.partition + " to internal buffers");
        }
    }

    public void ack(Long offset) {
        _pending.remove(offset);
    }

    public void fail(Long offset) {
        //TODO: should it use in-memory ack set to skip anything that's been acked but not committed???
        // things might get crazy with lots of timeouts
        if(_emittedToOffset > offset) {
            _emittedToOffset = offset;
            _pending.tailSet(offset).clear();
        }
    }

    public void commit() {
        LOG.info("Committing offset for " + _partition);
        long committedTo;
        if(_pending.isEmpty()) {
            committedTo = _emittedToOffset;
        } else {
            committedTo = _pending.first();
        }
        if(committedTo!=_committedTo) {
            LOG.info("Writing committed offset to ZK: " + committedTo);

            Map<Object, Object> data = (Map<Object,Object>)ImmutableMap.builder()
                .put("topology", ImmutableMap.of("id", _topologyInstanceId,
                                                 "name", _stormConf.get(Config.TOPOLOGY_NAME)))
                .put("offset", committedTo)
                .put("partition", _partition.partition)
                .put("broker", ImmutableMap.of("host", _partition.host.host,
                                               "port", _partition.host.port))
                .put("topic", _spoutConfig.topic).build();
	    _state.writeJSON(committedPath(), data);

            LOG.info("Wrote committed offset to ZK: " + committedTo);
            _committedTo = committedTo;
        }
        LOG.info("Committed offset " + committedTo + " for " + _partition);
    }

    private String committedPath() {
        return _spoutConfig.zkRoot + "/" + _spoutConfig.id + "/" + _partition;
    }

    public long queryPartitionOffsetLatestTime() {
        return _consumer.getOffsetsBefore(_spoutConfig.topic, _partition.partition,
                                          OffsetRequest.LatestTime(), 1)[0];
    }

    public long lastCommittedOffset() {
        return _committedTo;
    }

    public long lastCompletedOffset() {
        if(_pending.isEmpty()) {
            return _emittedToOffset;
        } else {
            return _pending.first();
        }
    }

    public GlobalPartitionId getPartition() {
        return _partition;
    }

    public void close() {
        _connections.unregister(_partition.host, _partition.partition);
    }
}
