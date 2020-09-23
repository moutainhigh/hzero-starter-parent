package org.hzero.websocket.helper;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import org.apache.commons.collections4.CollectionUtils;
import org.hzero.core.base.BaseConstants;
import org.hzero.websocket.constant.WebSocketConstant;
import org.hzero.websocket.redis.BrokerListenRedis;
import org.hzero.websocket.redis.BrokerServerSessionRedis;
import org.hzero.websocket.registry.BaseSessionRegistry;
import org.hzero.websocket.registry.GroupSessionRegistry;
import org.hzero.websocket.util.SocketSessionUtils;
import org.hzero.websocket.vo.ClientVO;
import org.hzero.websocket.vo.MsgVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.choerodon.core.exception.CommonException;

/**
 * 密钥连接websocket发送工具
 *
 * @author shuangfei.zhu@hand-china.com 2018/11/05 15:16
 */
@Component
public class KeySocketSendHelper {

    private final ObjectMapper objectMapper;
    private final RedisTemplate<String, String> redisTemplate;

    @Autowired
    public KeySocketSendHelper(ObjectMapper objectMapper,
                               RedisTemplate<String, String> redisTemplate) {
        this.objectMapper = objectMapper;
        this.redisTemplate = redisTemplate;
    }

    /**
     * 指定sessionId发送webSocket消息
     *
     * @param sessionId sessionId
     * @param key       自定义的key
     * @param message   消息内容
     */
    public void sendBySession(String sessionId, String key, String message) {
        try {
            MsgVO msg = new MsgVO().setSessionId(sessionId).setKey(key).setMessage(message).setType(WebSocketConstant.SendType.S_SESSION).setBrokerId(BaseSessionRegistry.getBrokerId());
            String msgStr = objectMapper.writeValueAsString(msg);
            // 优先本地消费
            WebSocketSession clientSession = GroupSessionRegistry.getSession(sessionId);
            if (clientSession != null) {
                SocketSessionUtils.sendMsg(clientSession, sessionId, msgStr);
                return;
            }
            // 通知其他服务
            redisTemplate.convertAndSend(WebSocketConstant.CHANNEL, msgStr);
        } catch (JsonProcessingException e) {
            throw new CommonException(BaseConstants.ErrorCode.ERROR, e);
        }
    }

    /**
     * 指定sessionId发送webSocket消息
     *
     * @param sessionId sessionId
     * @param key       自定义的key
     * @param data      数据
     */
    public void sendBySession(String sessionId, String key, byte[] data) {
        try {
            MsgVO msg = new MsgVO().setSessionId(sessionId).setKey(key).setData(data).setType(WebSocketConstant.SendType.S_SESSION).setBrokerId(BaseSessionRegistry.getBrokerId());
            // 优先本地消费
            WebSocketSession clientSession = GroupSessionRegistry.getSession(sessionId);
            if (clientSession != null) {
                SocketSessionUtils.sendMsg(clientSession, sessionId, data);
                return;
            }
            // 通知其他服务
            redisTemplate.convertAndSend(WebSocketConstant.CHANNEL, objectMapper.writeValueAsString(msg));
        } catch (JsonProcessingException e) {
            throw new CommonException(BaseConstants.ErrorCode.ERROR, e);
        }
    }

    /**
     * 指定group发送webSocket消息
     *
     * @param group   分组
     * @param key     自定义的key
     * @param message 消息内容
     */
    public void sendByGroup(String group, String key, String message) {
        try {
            String brokerId = BaseSessionRegistry.getBrokerId();
            MsgVO msg = new MsgVO().setGroup(group).setKey(key).setMessage(message).setType(WebSocketConstant.SendType.S_GROUP).setBrokerId(brokerId);
            String msgStr = objectMapper.writeValueAsString(msg);
            // 优先本地消费
            List<ClientVO> clientList = BrokerServerSessionRedis.getCache(brokerId, group);
            List<String> sessionIdList = clientList.stream().map(ClientVO::getSessionId).collect(Collectors.toList());
            SocketSessionUtils.sendClientMsg(sessionIdList, msgStr);
            // 通知其他服务
            notice(group, msgStr);
        } catch (JsonProcessingException e) {
            throw new CommonException(BaseConstants.ErrorCode.ERROR, e);
        }
    }

    /**
     * 指定group发送webSocket消息
     *
     * @param group 分组
     * @param key   自定义的key
     * @param data  数据
     */
    public void sendByGroup(String group, String key, byte[] data) {
        try {
            String brokerId = BaseSessionRegistry.getBrokerId();
            MsgVO msg = new MsgVO().setGroup(group).setKey(key).setData(data).setType(WebSocketConstant.SendType.S_GROUP).setBrokerId(brokerId);
            // 优先本地消费
            List<ClientVO> clientList = BrokerServerSessionRedis.getCache(brokerId, group);
            List<String> sessionIdList = clientList.stream().map(ClientVO::getSessionId).collect(Collectors.toList());
            SocketSessionUtils.sendClientMsg(sessionIdList, data);
            // 通知其他服务
            notice(group, objectMapper.writeValueAsString(msg));
        } catch (JsonProcessingException e) {
            throw new CommonException(BaseConstants.ErrorCode.ERROR, e);
        }
    }

    /**
     * 向所有服务发送webSocket消息
     *
     * @param key     自定义的key
     * @param message 消息内容
     */
    public void sendToAll(String key, String message) {
        try {
            String brokerId = BaseSessionRegistry.getBrokerId();
            MsgVO msg = new MsgVO().setKey(key).setMessage(message).setType(WebSocketConstant.SendType.S_ALL).setBrokerId(brokerId);
            String msgStr = objectMapper.writeValueAsString(msg);
            // 优先本地消费
            List<ClientVO> clients = BrokerServerSessionRedis.getCache(brokerId);
            List<String> sessionIdList = clients.stream().map(ClientVO::getSessionId).collect(Collectors.toList());
            SocketSessionUtils.sendClientMsg(sessionIdList, msgStr);
            // 通知远程
            redisTemplate.convertAndSend(WebSocketConstant.CHANNEL, msgStr);
        } catch (JsonProcessingException e) {
            throw new CommonException(BaseConstants.ErrorCode.ERROR, e);
        }
    }

    /**
     * 向所有服务发送webSocket消息
     *
     * @param key  自定义的key
     * @param data 数据
     */
    public void sendToAll(String key, byte[] data) {
        try {
            String brokerId = BaseSessionRegistry.getBrokerId();
            MsgVO msg = new MsgVO().setKey(key).setData(data).setType(WebSocketConstant.SendType.S_ALL).setBrokerId(brokerId);
            // 优先本地消费
            List<ClientVO> clients = BrokerServerSessionRedis.getCache(brokerId);
            List<String> sessionIdList = clients.stream().map(ClientVO::getSessionId).collect(Collectors.toList());
            SocketSessionUtils.sendClientMsg(sessionIdList, data);
            // 通知远程
            redisTemplate.convertAndSend(WebSocketConstant.CHANNEL, objectMapper.writeValueAsString(msg));
        } catch (JsonProcessingException e) {
            throw new CommonException(BaseConstants.ErrorCode.ERROR, e);
        }
    }

    /**
     * 关闭分组的所有连接
     *
     * @param group 分组
     */
    public void closeSessionByGroup(String group) {
        try {
            String brokerId = BaseSessionRegistry.getBrokerId();
            MsgVO msg = new MsgVO().setType(WebSocketConstant.SendType.CLOSE).setGroup(group).setBrokerId(brokerId);
            // 优先本地消费
            List<ClientVO> list = BrokerServerSessionRedis.getCache(brokerId, group);
            List<String> sessionIdList = list.stream().map(ClientVO::getSessionId).collect(Collectors.toList());
            SocketSessionUtils.closeSession(sessionIdList);
            // 通知远程
            redisTemplate.convertAndSend(WebSocketConstant.CHANNEL, objectMapper.writeValueAsString(msg));
        } catch (JsonProcessingException e) {
            throw new CommonException(BaseConstants.ErrorCode.ERROR, e);
        }
    }

    /**
     * 指定服务通道广播
     */
    private void notice(String group, String msgStr) {
        // 获取所有实例
        String brokerId = BaseSessionRegistry.getBrokerId();
        List<String> brokerList = BrokerListenRedis.getCache();
        brokerList.forEach(item -> {
            if (Objects.equals(item, brokerId)) {
                return;
            }
            List<ClientVO> list = BrokerServerSessionRedis.getCache(item, group);
            if (CollectionUtils.isNotEmpty(list)) {
                redisTemplate.convertAndSend(item, msgStr);
            }
        });
    }
}
