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

package org.apache.cassandra.erasurecode;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;

public class ECConfig
{
    public final static int DATA_SHARDS = 3;
    public final static int PARITY_SHARDS = 2;
    public final static int TOTAL_SHARDS = 5;
    //public final static int num_intersect = 1;

    public  static int wholeValueFound = 0;

    public static final String[] ADDRESSES = {"10.0.0.20","10.0.0.186","10.0.0.106","10.0.0.15"};

    private static HashMap<String, Integer> map = new HashMap<>();

    //public static final int QUORUM = (int) Math.ceil ( (ECConfig.num_server + ECConfig.num_intersect) / 2);


    // Convert the byte array to String to send back to client
    public static String byteToString(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }

    // Convert incoming String value
    public static byte[] stringToByte(String value) {
       return value.getBytes(StandardCharsets.UTF_8);
       }

    // Create the Empty Codes based on what I set
    public static byte[] emptyCodes(int length) {
        byte[] arr = new byte[length];
        for (int i = 0; i < length; i++) {
            arr[i] = '0';
        }
        return arr;
    }

    public static void initiateAddressMap() {
        for (int i = 0; i < ADDRESSES.length; i++) {
            map.put(ADDRESSES[i], i);
        }
    }

    public static HashMap<String, Integer> getAddressMap() {
        return map;
    }

}
