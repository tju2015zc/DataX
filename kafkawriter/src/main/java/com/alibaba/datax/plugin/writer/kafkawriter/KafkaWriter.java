package com.alibaba.datax.plugin.writer.kafkawriter;

import com.alibaba.datax.common.element.Column;
import com.alibaba.datax.common.element.Record;
import com.alibaba.datax.common.exception.DataXException;
import com.alibaba.datax.common.plugin.RecordReceiver;
import com.alibaba.datax.common.spi.Writer;
import com.alibaba.datax.common.util.Configuration;
import com.alibaba.fastjson.JSON;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.ListTopicsResult;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.Callback;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * @author Peter
 * @createTime Created in 2022/1/10 20:30
 * @description:
 */

public class KafkaWriter extends Writer {

    public static class Job extends Writer.Job {
        private static final Logger logger = LoggerFactory.getLogger(Job.class);
        private Configuration conf = null;

        @Override
        public List<Configuration> split(int mandatoryNumber) {
            // 按照reader配置文件的格式来组织相同个数的writer配置文件
            List<Configuration> configurations = new ArrayList<Configuration>(mandatoryNumber);
            for (int i = 0; i < mandatoryNumber; i++) {
                configurations.add(this.conf.clone());
            }
            return configurations;
        }

        private void validateParameter() {
            this.conf.getNecessaryValue(Key.BOOTSTRAP_SERVERS, KafkaWriterErrorCode.REQUIRED_VALUE);
            this.conf.getNecessaryValue(Key.TOPIC, KafkaWriterErrorCode.REQUIRED_VALUE);
        }

        @Override
        public void init() {
            this.conf = super.getPluginJobConf();
            logger.info("Kafka Writer Job configs:{}", JSON.toJSONString(this.conf));
            this.validateParameter();
        }

        @Override
        public void destroy() {
            logger.info("Kafka Writer Job destroy");
        }
    }

    public static class Task extends Writer.Task {
        private static final Logger logger = LoggerFactory.getLogger(Task.class);
        private static final String NEWLINE_FLAG = System.getProperty("line.separator", "\n");

        private Producer<String, String> producer;
        private String fieldDelimiter;
        private Configuration conf;
        private Properties props;

        @Override
        public void init() {
            this.conf = super.getPluginJobConf(); //获取配置文件信息{parameter 里面的参数}
            fieldDelimiter = conf.getUnnecessaryValue(Key.FIELD_DELIMITER, "\t", null);

            props = new Properties();
            props.put("bootstrap.servers", conf.getString(Key.BOOTSTRAP_SERVERS));
            // 意味着leader需要等待所有备份都成功写入日志，这种策略会保证只要有一个备份存活就不会丢失数据。这是最强的保证。
            props.put("acks", conf.getUnnecessaryValue(Key.ACK, "0", null));
            props.put("retries", conf.getUnnecessaryValue(Key.RETRIES, "0", null));
            // 控制发送者在发布到kafka之前等待批处理的字节数。
            // 满足batch.size和ling.ms之一，producer便开始发送消息
            // 默认16384   16kb
            props.put("batch.size", conf.getUnnecessaryValue(Key.BATCH_SIZE, "16384", null));
            props.put("linger.ms", 1);
            props.put("key.serializer", conf.getUnnecessaryValue(Key.KEYSERIALIZER, "org.apache.kafka.common.serialization.StringSerializer", null));
            props.put("value.serializer", conf.getUnnecessaryValue(Key.VALUESERIALIZER, "org.apache.kafka.common.serialization.StringSerializer", null));
            logger.info("Kafka Writer Task init configs: {}", JSON.toJSONString(props));
            producer = new KafkaProducer<String, String>(props);
        }

        @Override
        public void prepare() {
            if (Boolean.valueOf(conf.getUnnecessaryValue(Key.NO_TOPIC_CREATE, "false", null))) {
                ListTopicsResult topicsResult = AdminClient.create(props).listTopics();
                String topic = conf.getNecessaryValue(Key.TOPIC, KafkaWriterErrorCode.REQUIRED_VALUE);
                try {
                    if (!topicsResult.names().get().contains(topic)) {
                        new NewTopic(
                            topic,
                            Integer.valueOf(conf.getUnnecessaryValue(Key.TOPIC_NUM_PARTITION, "1", null)),
                            Short.valueOf(conf.getUnnecessaryValue(Key.TOPIC_REPLICATION_FACTOR, "1", null))
                        );
                        List<NewTopic> newTopics = new ArrayList<NewTopic>();
                        AdminClient.create(props).createTopics(newTopics);
                    }
                } catch (Exception e) {
                    throw new DataXException(KafkaWriterErrorCode.CREATE_TOPIC, KafkaWriterErrorCode.REQUIRED_VALUE.getDescription());
                }
            }
        }

        @Override
        public void startWrite(RecordReceiver lineReceiver) {
            logger.info("Kafka Writer Task start to writer kafka");
            Record record = null;
            // 获取输出格式，默认json
            String writeType = conf.getUnnecessaryValue(Key.WRITE_TYPE, WriteTypeEnum.JSON.name(), null);
            WriteTypeEnum writeTypeEnum = WriteTypeEnum.getWriteTypeEnumByName(writeType);
            while ((record = lineReceiver.getFromReader()) != null) { // 说明还在读取数据,或者读取的数据没处理完
                // 获取一行数据，按照指定分隔符 拼成字符串 发送出去
                switch (writeTypeEnum) {
                    case JSON:
                        producer.send(new ProducerRecord<String, String>(this.conf.getString(Key.TOPIC), record.toString()),
                            new WriterCallback(System.currentTimeMillis())
                        );
                        break;
                    case TEXT:
                        producer.send(new ProducerRecord<String, String>(this.conf.getString(Key.TOPIC), recordToString(record)),
                            new WriterCallback(System.currentTimeMillis())
                        );
                        break;
                    default:
                        break;
                }
                logger.info("complete write " + record.toString());
                producer.flush();
            }
        }

        @Override
        public void destroy() {
            if (producer != null) {
                producer.close();
            }
        }

        /**
         * 数据格式化
         * @param record
         * @return
         */
        private String recordToString(Record record) {
            int recordLength = record.getColumnNumber();
            if (0 == recordLength) {
                return NEWLINE_FLAG;
            }
            Column column;
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < recordLength; i++) {
                column = record.getColumn(i);
                sb.append(column.asString()).append(fieldDelimiter);
            }
            sb.setLength(sb.length() - 1);
            sb.append(NEWLINE_FLAG);
            return sb.toString();
        }

        public class WriterCallback implements Callback {
            private final Logger logger = LoggerFactory.getLogger(WriterCallback.class);
            private long startTime;

            public WriterCallback(long startTime) {
                this.startTime = startTime;
            }

            @Override
            public void onCompletion(RecordMetadata metadata, Exception exception) {
                if (exception != null) {
                    logger.error("Error sending message to Kafka {} ", exception.getMessage());
                }

                if (logger.isDebugEnabled()) {
                    long eventElapsedTime = System.currentTimeMillis() - startTime;
                    logger.debug("Acked message partition:{} ofset:{}",  metadata.partition(), metadata.offset());
                    logger.debug("Elapsed time for send: {}", eventElapsedTime);
                }
            }
        }
    }
}
