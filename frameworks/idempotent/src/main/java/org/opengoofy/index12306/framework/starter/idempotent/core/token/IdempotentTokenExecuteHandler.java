/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.opengoofy.index12306.framework.starter.idempotent.core.token;

import cn.hutool.core.util.StrUtil;
import com.google.common.base.Strings;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.aspectj.lang.ProceedingJoinPoint;
import org.opengoofy.index12306.framework.starter.cache.DistributedCache;
import org.opengoofy.index12306.framework.starter.convention.errorcode.BaseErrorCode;
import org.opengoofy.index12306.framework.starter.convention.exception.ClientException;
import org.opengoofy.index12306.framework.starter.idempotent.config.IdempotentProperties;
import org.opengoofy.index12306.framework.starter.idempotent.core.AbstractIdempotentExecuteHandler;
import org.opengoofy.index12306.framework.starter.idempotent.core.IdempotentParamWrapper;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Optional;
import java.util.UUID;

/**
 * 基于 Token 验证请求幂等性, 通常应用于 RestAPI 方法
 * 公众号：马丁玩编程，回复：加群，添加马哥微信（备注：12306）获取项目资料
 */

//在请求头或参数中携带令牌
//如果令牌在 Redis 中存在并且能够成功删除，说明是新的请求；如果令牌不存在或者无法删除，就认为是重复请求并阻止它。
@RequiredArgsConstructor
public final class IdempotentTokenExecuteHandler extends AbstractIdempotentExecuteHandler implements IdempotentTokenService {

    private final DistributedCache distributedCache;
    private final IdempotentProperties idempotentProperties;

    private static final String TOKEN_KEY = "token";
    private static final String TOKEN_PREFIX_KEY = "idempotent:token:";
    private static final long TOKEN_EXPIRED_TIME = 6000;

    @Override
    protected IdempotentParamWrapper buildWrapper(ProceedingJoinPoint joinPoint) {
        return new IdempotentParamWrapper();
    }

    @Override
    public String createToken() {
        String token = Optional
                .ofNullable(Strings.emptyToNull(idempotentProperties.getPrefix()))
                .orElse(TOKEN_PREFIX_KEY) + UUID.randomUUID();
        distributedCache
                .put(token, "",
                        Optional.ofNullable(idempotentProperties.getTimeout())
                                .orElse(TOKEN_EXPIRED_TIME));
        return token;
    }

    @Override
    public void handler(IdempotentParamWrapper wrapper) {
        HttpServletRequest request = ((ServletRequestAttributes) (RequestContextHolder.currentRequestAttributes())).getRequest();
        String token = request.getHeader(TOKEN_KEY);
        //从请求的头信息中获取令牌。如果头信息中没有令牌，则尝试从请求参数中获取。
        if (StrUtil.isBlank(token)) {
            token = request.getParameter(TOKEN_KEY);
            if (StrUtil.isBlank(token)) {
                //如果两者都没有找到令牌，则抛出异常 BaseErrorCode.IDEMPOTENT_TOKEN_NULL_ERROR，表示幂等性令牌为空的错误。
                throw new ClientException(BaseErrorCode.IDEMPOTENT_TOKEN_NULL_ERROR);
            }
        }

        //token存在便删除缓存中的token字段，使用就删除
        Boolean tokenDelFlag = distributedCache.delete(token);
        if (!tokenDelFlag) {
            String errMsg = StrUtil.isNotBlank(wrapper.getIdempotent().message())
                    ? wrapper.getIdempotent().message()
                    : BaseErrorCode.IDEMPOTENT_TOKEN_DELETE_ERROR.message();
            throw new ClientException(errMsg, BaseErrorCode.IDEMPOTENT_TOKEN_DELETE_ERROR);
        }
    }
}
