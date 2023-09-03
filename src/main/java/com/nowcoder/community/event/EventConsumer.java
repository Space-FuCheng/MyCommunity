package com.nowcoder.community.event;

import com.alibaba.fastjson.JSONObject;
import com.nowcoder.community.entity.DiscussPost;
import com.nowcoder.community.entity.Event;
import com.nowcoder.community.entity.Message;
import com.nowcoder.community.service.DiscussPostService;
import com.nowcoder.community.service.ElasticsearchService;
import com.nowcoder.community.service.MessageService;
import com.nowcoder.community.util.CommunityConstant;
import com.nowcoder.community.util.CommunityUtil;
import com.nowcoder.community.util.RedisKeyUtil;
import com.qiniu.common.QiniuException;
import com.qiniu.common.Zone;
import com.qiniu.http.Response;
import com.qiniu.storage.Configuration;
import com.qiniu.storage.UploadManager;
import com.qiniu.util.Auth;
import com.qiniu.util.StringMap;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Future;

@Component
public class EventConsumer implements CommunityConstant {

    private static final Logger logger = LoggerFactory.getLogger(EventConsumer.class);

    @Autowired
    private MessageService messageService;
    @Autowired
    private RedisTemplate redisTemplate;

    @Autowired
    private DiscussPostService discussPostService;

    @Autowired
    private ElasticsearchService elasticsearchService;

    @Value("${wk.image.command}")
    private String wkImageCommand;

    @Value("${wk.image.storage}")
    private String wkImageStorage;

    @Value("${qiniu.key.access}")
    private String accessKey;

    @Value("${qiniu.key.secret}")
    private String secretKey;

    @Value("${qiniu.bucket.share.name}")
    private String shareBucketName;

    @Autowired
    private ThreadPoolTaskScheduler taskScheduler;

    @Autowired
    private KafkaTemplate kafkaTemplate;
    @Autowired
    private EventProducer eventProducer;





    @KafkaListener(topics = {TOPIC_COMMENT, TOPIC_LIKE, TOPIC_FOLLOW})
    public void handleCommentMessage(ConsumerRecord record) {
        if (record == null || record.value() == null) {
            logger.error("消息的内容为空!");
            return;
        }

        Event event = JSONObject.parseObject(record.value().toString(), Event.class);
        if (event == null) {
            logger.error("消息格式错误!");
            return;
        }

        // 发送站内通知
        Message message = new Message();
        message.setFromId(SYSTEM_USER_ID);
        message.setToId(event.getEntityUserId());
        message.setConversationId(event.getTopic());
        message.setCreateTime(new Date());

        Map<String, Object> content = new HashMap<>();
        content.put("userId", event.getUserId());
        content.put("entityType", event.getEntityType());
        content.put("entityId", event.getEntityId());

        if (!event.getData().isEmpty()) {
            for (Map.Entry<String, Object> entry : event.getData().entrySet()) {
                content.put(entry.getKey(), entry.getValue());
            }
        }

        message.setContent(JSONObject.toJSONString(content));
        messageService.addMessage(message);
    }

    // 消费发帖事件
    @KafkaListener(topics = {TOPIC_PUBLISH})
    public void handlePublishMessage(ConsumerRecord record) {
        if (record == null || record.value() == null) {
            logger.error("消息的内容为空!");
            return;
        }

        Event event = JSONObject.parseObject(record.value().toString(), Event.class);
        if (event == null) {
            logger.error("消息格式错误!");
            return;
        }

        DiscussPost post = discussPostService.findDiscussPostById(event.getEntityId());
        elasticsearchService.saveDiscussPost(post);
    }

    // 消费删帖事件
    @KafkaListener(topics = {TOPIC_DELETE})
    public void handleDeleteMessage(ConsumerRecord record) {
        if (record == null || record.value() == null) {
            logger.error("消息的内容为空!");
            return;
        }

        Event event = JSONObject.parseObject(record.value().toString(), Event.class);
        if (event == null) {
            logger.error("消息格式错误!");
            return;
        }
        elasticsearchService.deleteDiscussPost(event.getEntityId());
    }
    @KafkaListener(topics = TOPIC_SHARE)
    public void handleShareMessage(ConsumerRecord record) {
        if (record == null || record.value() == null) {
            logger.error("消息的内容为空!");
            return;
        }

        Event event = JSONObject.parseObject(record.value().toString(), Event.class);
        if (event == null) {
            logger.error("消息格式错误!");
            return;
        }

        String htmlUrl = (String) event.getData().get("htmlUrl");
        String fileName = (String) event.getData().get("fileName");
        String suffix = (String) event.getData().get("suffix");
//注意" --quality 75 "左右的空格
        String cmd = wkImageCommand + " --quality 75 "
                + htmlUrl + " " + wkImageStorage + "/" + fileName + suffix;
        try {
            Runtime.getRuntime().exec(cmd);
            logger.info("生成长图成功" + cmd);
        } catch (IOException e) {
            logger.error("生成长图失败" + cmd);
        }

//        上传到七牛云，用定时器来等这图片生成，比如0.5s看一次生成没，没有就继续等
        UploadTask task = new UploadTask(fileName, suffix);
        Future future = taskScheduler.scheduleAtFixedRate(task, 5000); //定时器5000ms后才开始，并以此为周期循环，赶紧吧这个状态传给task
        task.setFuture(future);

    }

    class UploadTask implements Runnable {
//        文件名称
        private String fileName;

//        文件后缀
        private String suffix;
//        启动任务的返回值
        private Future future;
//        开始时间 ,这俩为了保证上传失败时会停止
        private long startTime;
//        上传次数
        private int uploadTimes;
        public UploadTask(String fileName, String suffix) {
            this.fileName = fileName;
            this.suffix = suffix;
            this.startTime = System.currentTimeMillis();
        }

        public void setFuture(Future future) {
            this.future = future;
        }

        @Override
        public void run() {
//            生成失败
            if (System.currentTimeMillis() - startTime > 30000) {
                logger.error("时间过长，终止任务：" + fileName);
                future.cancel(true); //终止
                return ;
            }
//            上传失败
            if (uploadTimes >= 3) {
                logger.error("上传次数过多，终止任务：" + fileName);
                future.cancel(true); //终止
                return ;
            }

            String path = wkImageStorage + "/" + fileName + suffix;
            File file = new File(path);
            if (file.exists()) {
                logger.info(String.format("开始第%d次上传[%s].", ++uploadTimes, fileName));
//                设置响应信息
                StringMap policy = new StringMap();
                policy.put("returnBody", CommunityUtil.getJSONString(0));
//        生成上传凭证
                Auth auth = Auth.create(accessKey, secretKey);
                String uploadToken = auth.uploadToken(shareBucketName, fileName, 3600, policy);
//                指定上传机房
                UploadManager manager = new UploadManager(new Configuration(Zone.zone0()));
                try {
//                    开始上传图片
                    Response response = manager.put(
                            path, fileName, uploadToken, null, "image/" + suffix.substring(suffix.lastIndexOf(".")+1), false);
//                    处理响应结果
                    JSONObject json = JSONObject.parseObject(response.bodyString());
                    if (json == null || json.get("code") == null || !json.get("code").toString().equals("0")) {
                        logger.info(String.format("第%d次上传失败[%s].", uploadTimes, fileName));
                    } else {
                        logger.info(String.format("第%d次上传成功[%s].", uploadTimes, fileName));
                        future.cancel(true); //成功了任务也可以结束了
                    }

                } catch (QiniuException e) {
                    logger.info(String.format("第%d次上传失败[%s].", uploadTimes, fileName));
                }
            } else {
                logger.info("等待图片生成[" + fileName + "].");

            }

        }
    }
    @KafkaListener(topics = {TOPIC_DELETE_USER_PROFILE_CACHE})
    public void handleDeleteUserProfileCache(ConsumerRecord record) {
        logger.info("Received message: " + record.value());
        if (record == null || record.value() == null) {
            logger.error("消息的内容为空!");
            return;
        }

        Event event = JSONObject.parseObject(record.value().toString(), Event.class);
        if (event == null) {
            logger.error("消息格式错误!");
            return;
        }
        int userId = event.getUserId();
        //并不是直接传入userId，应该是传入RedisKey
        String redisKey = RedisKeyUtil.getUserKey(userId);
        boolean deleteResult = redisTemplate.delete(redisKey);
        if (!deleteResult) {
            // 删除缓存失败，可以进行重试

            event.getData().put("retryCount", (int)event.getData().getOrDefault("retryCount", 0) + 1);
            int retryCount = (int)event.getData().get("retryCount");

            if ( retryCount <= MAX_RETRY_COUNT) {
                //添加重试间隔，不要秒发。这样会等待3s后再重试
                int retryIntervalSeconds = 3;
                try {
                    Thread.sleep(retryIntervalSeconds * 1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    logger.error("重试间隔等待被中断", e);
                }
                logger.info("删除失败，正在重试第" + retryCount + "次");
                eventProducer.fireEvent(event);
            } else {
                logger.error("超过最大重试次数，删除缓存失败",event);
            }
        } else {
            logger.info("删除成功 ");
        }
    }

}
