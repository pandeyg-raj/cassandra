/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.cassandra.db;

import org.apache.cassandra.net.IVerbHandler;
import org.apache.cassandra.net.Message;
import org.apache.cassandra.net.MessagingService;
import org.apache.cassandra.utils.concurrent.AsyncPromise;

import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static org.apache.cassandra.utils.MonotonicClock.Global.approxTime;

/**
 * Handles EC_SIGNAL_REQ messages on the dedicated EC_SIGNAL stage, completely
 * decoupled from Stage.MUTATION so signal processing cannot delay regular writes.
 * No response is sent back — the coordinator fires and forgets.
 */
public class EcSignalVerbHandler implements IVerbHandler<Mutation>
{
    public static final EcSignalVerbHandler instance = new EcSignalVerbHandler();

    @Override
    public void doVerb(Message<Mutation> message)
    {
        if (approxTime.now() > message.expiresAtNanos())
        {
            MessagingService.instance().metrics.recordDroppedMessage(message, message.elapsedSinceCreated(NANOSECONDS), NANOSECONDS);
            return;
        }

        Mutation mutation = message.payload;
        Keyspace.open(mutation.getKeyspaceName())
                .applySignalRMW(mutation, true, true, true, true, new AsyncPromise<>());
    }
}
