package com.hmdp.utils;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class RedisData {  // 用来封装shop，给他添加了一个逻辑过期时间拉
    private LocalDateTime expireTime;
    private Object data;
}
