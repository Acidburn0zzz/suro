/*
 * Copyright 2013 Netflix, Inc.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

package com.netflix.suro;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Splitter;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.io.Closeables;
import com.google.inject.Injector;
import com.netflix.governator.configuration.PropertiesConfigurationProvider;
import com.netflix.governator.guice.BootstrapBinder;
import com.netflix.governator.guice.BootstrapModule;
import com.netflix.governator.guice.LifecycleInjector;
import com.netflix.governator.lifecycle.LifecycleManager;
import com.netflix.suro.client.SuroClient;
import com.netflix.suro.message.Message;
import com.netflix.suro.routing.RoutingMap;
import com.netflix.suro.routing.RoutingPlugin;
import com.netflix.suro.routing.TestMessageRouter;
import com.netflix.suro.server.ServerConfig;
import com.netflix.suro.server.StatusServer;
import com.netflix.suro.sink.Sink;
import com.netflix.suro.sink.SinkManager;
import com.netflix.suro.sink.TestSinkManager;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.junit.Assert.assertTrue;

public class TestSuroServer {
    private static final Properties properties = new Properties();
    private static final String sinkDesc = "{\n" +
            "    \"default\": {\n" +
            "        \"type\": \"TestSink\",\n" +
            "        \"message\": \"default\"\n" +
            "    },\n" +
            "    \"sink1\": {\n" +
            "        \"type\": \"TestSink\",\n" +
            "        \"message\": \"sink1\"\n" +
            "    }\n" +
            "}";
    private final String mapDesc = "{\n" +
            "    \"topic1\": {\n" +
            "        \"where\": [\n" +
            "            {\"sink\" : \"sink1\"},\n" +
            "            {\"sink\" : \"default\"}\n" +
            "        ]\n" +
            "    },\n" +
            "    \"topic2\": {\n" +
            "        \"where\": [\n" +
            "            {\"sink\" : \"sink1\"}\n" +
            "        ]\n" +
            "    },\n" +
            "    \"topic4\": {\n" +
            "        \"where\": [{\n" +
            "             \"sink\" : \"sink1\",\n" +
            "             \"filter\" : {\n" +
            "                 \"type\" : \"xpath\",\n" +
            "                 \"filter\" : \"xpath(\\\"//foo/bar\\\") =~ \\\"^value[02468]$\\\"\",\n" +
            "                 \"converter\" : {\n" +
            "                      \"type\" : \"jsonmap\"\n" +
            "                   }\n" +
            "             }\n" +
            "       }]\n" +
            "    }\n" +
            "}";

    @Before
    public void setup() {
        properties.setProperty(ServerConfig.MESSAGE_ROUTER_THREADS, "1");
    }

    @After
    public void tearDown() {
        properties.clear();
    }

    @Test
    public void test() throws Exception {
        LifecycleManager manager = null;

        try {
            // Create the injector
            Injector injector = LifecycleInjector.builder()
                    .withBootstrapModule(
                        new BootstrapModule() {
                            @Override
                            public void configure(BootstrapBinder binder) {
                                binder.bindConfigurationProvider().toInstance(
                                      new PropertiesConfigurationProvider(properties));
                            }
                        }
                     )
                    .withModules(
                        new RoutingPlugin(),
                        new SuroModule(),
                        new SuroPlugin() {
                            @Override
                            protected void configure() {
                                this.addSinkType("TestSink", TestMessageRouter.TestSink.class);
                            }
                        },
                        StatusServer.createJerseyServletModule()
                     )
                    .createInjector();
    
            manager = injector.getInstance(LifecycleManager.class);
            manager.start();
            
            SinkManager  sinkManager = injector.getInstance(SinkManager.class);
            RoutingMap   routes      = injector.getInstance(RoutingMap.class);
            ObjectMapper mapper      = injector.getInstance(ObjectMapper.class);
            
            sinkManager.set((Map<String, Sink>)mapper.readValue(sinkDesc, new TypeReference<Map<String, Sink>>(){}));
            routes     .set((Map<String, RoutingMap.RoutingInfo>)mapper.readValue(mapDesc, new TypeReference<Map<String, RoutingMap.RoutingInfo>>(){}));

            // create the client
            final Properties clientProperties = new Properties();
            clientProperties.setProperty(ClientConfig.LB_TYPE, "static");
            clientProperties.setProperty(ClientConfig.LB_SERVER, "localhost:7101");
            clientProperties.setProperty(ClientConfig.CLIENT_TYPE, "sync");

            SuroClient client = new SuroClient(clientProperties);

            for (int i = 0; i < 10; ++i) {
                client.send(new Message("topic1", Integer.toString(i).getBytes()));
            }
            for (int i = 0; i < 5; ++i) {
                client.send(new Message("topic2", Integer.toString(i).getBytes()));
            }
            for (int i = 0; i < 20; ++i) {
                client.send(new Message("topic3", Integer.toString(i).getBytes()));
            }

            for(int i = 0; i < 30; ++i) {
                Map<String, Object> message = makeMessage("foo/bar", "value"+i);
                client.send(new Message("topic4", mapper.writeValueAsBytes(message)));
            }

            int count = 10;
            while (!answer() && count > 0) {
                Thread.sleep(1000);
                --count;
            }

            assertTrue(answer());
            client.shutdown();
            
        } catch (Exception e) {
            System.err.println("SuroServer startup failed: " + e.getMessage());
            System.exit(-1);
        } finally {
            Closeables.close(manager, true);
        }
    }

    private boolean answer() {
        Integer sink1 = TestMessageRouter.messageCount.get("sink1");
        Integer defaultV = TestMessageRouter.messageCount.get("default");
        System.out.println(sink1);
        return sink1 != null && sink1 == 20 && defaultV != null && defaultV == 30;
    }

    private Map<String, Object> makeMessage(String path, Object value) {
        Splitter splitter = Splitter.on("/").omitEmptyStrings().trimResults();

        List<String> keys = Lists.newArrayList(splitter.split(path));
        Map<String, Object> result = Maps.newHashMap();
        Map<String, Object> current = result;
        for(int i = 0; i < keys.size() - 1; ++i) {
            Map<String, Object> map = Maps.newHashMap();
            current.put(keys.get(i), map);
            current = map;
        }

        current.put(keys.get(keys.size() - 1), value);

        return result;
    }
}
