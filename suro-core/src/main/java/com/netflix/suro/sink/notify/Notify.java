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

package com.netflix.suro.sink.notify;

import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * Notify interface is used to send a notify from the sink to somewhere.
 * This is a kind of pub-sub module, for example, QueueNotify is used in
 * communication between LocalFileSink and S3FileSink.
 *
 * @param <E> type of notify
 *
 * @author jbae
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
public interface Notify<E> {
    void init();
    boolean send(E message);
    E recv();
    String getStat();
}
