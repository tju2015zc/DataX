package com.alibaba.datax.plugin.reader.kafkareader;

import com.alibaba.fastjson.JSON;

import java.util.HashMap;

/**
 * @author Peter
 * @createTime Created in 2022/1/12 14:10
 * @description:
 */

public class JsonUtil {
    /**
     * Json字符串转换为Map
     * @param str
     * @return
     */
    public static HashMap<String ,Object> parseJsonStrToMap(String str){
        return JSON.parseObject(str, new HashMap<String, Object>().getClass());
    }
}
