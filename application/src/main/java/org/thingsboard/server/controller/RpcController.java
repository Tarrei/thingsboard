/**
 * Copyright © 2016-2018 The Thingsboard Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.thingsboard.server.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.util.concurrent.FutureCallback;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.DeferredResult;
import org.thingsboard.server.actors.plugin.ValidationResult;
import org.thingsboard.server.common.data.exception.ThingsboardErrorCode;
import org.thingsboard.server.common.data.exception.ThingsboardException;
import org.thingsboard.server.common.data.id.DeviceId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.rpc.ToDeviceRpcRequestBody;
import org.thingsboard.server.common.msg.rpc.ToDeviceRpcRequest;
import org.thingsboard.server.extensions.api.exception.ToErrorResponseEntity;
import org.thingsboard.server.extensions.api.plugins.PluginConstants;
import org.thingsboard.server.common.data.rpc.RpcRequest;
import org.thingsboard.server.service.rpc.LocalRequestMetaData;
import org.thingsboard.server.service.rpc.DeviceRpcService;
import org.thingsboard.server.service.security.AccessValidator;
import org.thingsboard.server.service.security.model.SecurityUser;

import javax.annotation.Nullable;
import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.io.IOException;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Created by ashvayka on 22.03.18.
 */
@RestController
@RequestMapping(PluginConstants.RPC_URL_PREFIX)
@Slf4j
public class RpcController extends BaseController {

    public static final int DEFAULT_TIMEOUT = 10000;
    protected final ObjectMapper jsonMapper = new ObjectMapper();

    @Autowired
    private DeviceRpcService deviceRpcService;

    @Autowired
    private AccessValidator accessValidator;

    private ExecutorService executor;

    @PostConstruct
    public void initExecutor() {
        executor = Executors.newSingleThreadExecutor();
    }

    @PreDestroy
    public void shutdownExecutor() {
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    @PreAuthorize("hasAnyAuthority('SYS_ADMIN', 'TENANT_ADMIN', 'CUSTOMER_USER')")
    @RequestMapping(value = "/oneway/{deviceId}", method = RequestMethod.POST)
    @ResponseBody
    public DeferredResult<ResponseEntity> handleOneWayDeviceRPCRequest(@PathVariable("deviceId") String deviceIdStr, @RequestBody String requestBody) throws ThingsboardException {
        return handleDeviceRPCRequest(true, new DeviceId(UUID.fromString(deviceIdStr)), requestBody);
    }

    @PreAuthorize("hasAnyAuthority('SYS_ADMIN', 'TENANT_ADMIN', 'CUSTOMER_USER')")
    @RequestMapping(value = "/twoway/{deviceId}", method = RequestMethod.POST)
    @ResponseBody
    public DeferredResult<ResponseEntity> handleTwoWayDeviceRPCRequest(@PathVariable("deviceId") String deviceIdStr, @RequestBody String requestBody) throws ThingsboardException {
        return handleDeviceRPCRequest(false, new DeviceId(UUID.fromString(deviceIdStr)), requestBody);
    }


    private DeferredResult<ResponseEntity> handleDeviceRPCRequest(boolean oneWay, DeviceId deviceId, String requestBody) throws ThingsboardException {
        try {
            JsonNode rpcRequestBody = jsonMapper.readTree(requestBody);
            RpcRequest cmd = new RpcRequest(rpcRequestBody.get("method").asText(),
                    jsonMapper.writeValueAsString(rpcRequestBody.get("params")));

            if (rpcRequestBody.has("timeout")) {
                cmd.setTimeout(rpcRequestBody.get("timeout").asLong());
            }
            SecurityUser currentUser = getCurrentUser();
            TenantId tenantId = currentUser.getTenantId();
            final DeferredResult<ResponseEntity> response = new DeferredResult<>();
            long timeout = System.currentTimeMillis() + (cmd.getTimeout() != null ? cmd.getTimeout() : DEFAULT_TIMEOUT);
            ToDeviceRpcRequestBody body = new ToDeviceRpcRequestBody(cmd.getMethodName(), cmd.getRequestData());
            accessValidator.validate(currentUser, deviceId, new HttpValidationCallback(response, new FutureCallback<DeferredResult<ResponseEntity>>() {
                @Override
                public void onSuccess(@Nullable DeferredResult<ResponseEntity> result) {

                    ToDeviceRpcRequest rpcRequest = new ToDeviceRpcRequest(UUID.randomUUID(),
                            tenantId,
                            deviceId,
                            oneWay,
                            timeout,
                            body
                    );
                    deviceRpcService.process(rpcRequest, new LocalRequestMetaData(rpcRequest, currentUser, result));
                }

                @Override
                public void onFailure(Throwable e) {
                    ResponseEntity entity;
                    if (e instanceof ToErrorResponseEntity) {
                        entity = ((ToErrorResponseEntity) e).toErrorResponseEntity();
                    } else {
                        entity = new ResponseEntity(HttpStatus.UNAUTHORIZED);
                    }
                    deviceRpcService.logRpcCall(currentUser, deviceId, body, oneWay, Optional.empty(), e);
                    response.setResult(entity);
                }
            }));
            return response;
        } catch (IOException ioe) {
            throw new ThingsboardException("Invalid request body", ioe, ThingsboardErrorCode.BAD_REQUEST_PARAMS);
        }
    }

}