package com.alibaba.datax.plugin.writer.kafkawriter;

import org.apache.commons.lang3.StringUtils;

/**
 * @author Peter
 * @createTime Created in 2022/1/10 20:32
 * @description:
 */

public enum WriteTypeEnum {
    JSON("json"),
    TEXT("text");

    private String name;

    WriteTypeEnum(String name) {
        this.name = name;
    }

    public static WriteTypeEnum getWriteTypeEnumByName(String name) {
        WriteTypeEnum[] writeTypeEnums = WriteTypeEnum.values();
        for (WriteTypeEnum writeTypeEnum : writeTypeEnums) {
            if (StringUtils.equalsIgnoreCase(name, writeTypeEnum.getName())) {
                return writeTypeEnum;
            }
        }
        return WriteTypeEnum.JSON;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }
}
