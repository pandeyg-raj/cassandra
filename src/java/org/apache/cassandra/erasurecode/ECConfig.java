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
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

import org.yaml.snakeyaml.Yaml;

public class ECConfig
{
    public static int DATA_SHARDS ;
    public static int PARITY_SHARDS ;
    public static int TOTAL_SHARDS ;
    public static String EC_COLUMN;
    public static String SignalStr ;
    /*
    = "signal," +
                       String.valueOf(ECConfig.TOTAL_SHARDS) +"," +
                       String.valueOf(ECConfig.DATA_SHARDS) +"," +
                       "8,10.158.34.18:0,10.158.34.23:1,10.158.34.24:2,10.158.34.25:3,10.158.34.26:4,10.0.0.51:0,10.0.0.52:1,10.0.0.53:2";
    */

    public  static int wholeValueFound = 0;
    public  static int TotalSignalReceived = 0;
    public  static int DecodingNeeded = 0;



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

    public static void initECConfig() {

        try {

            InputStream inputStream = new FileInputStream(new File("./conf/ECConfig.yaml"));
            Yaml yaml = new Yaml();
            Map<String, Object> data = yaml.load(inputStream);
            DATA_SHARDS = (int) data.get("data_shards");
            PARITY_SHARDS = (int) data.get("parity_shards");
            TOTAL_SHARDS = DATA_SHARDS + PARITY_SHARDS ;
            EC_COLUMN = data.get("ec_column").toString();
            SignalStr ="signal," +
                       String.valueOf(TOTAL_SHARDS) +"," +
                       String.valueOf(DATA_SHARDS) +"," +
                       data.get("ec_configs").toString();


        } catch (FileNotFoundException e) {
            throw new RuntimeException(e);
        }

    }

    public static HashMap<String, Integer> getAddressMap() {
        return map;
    }

}
