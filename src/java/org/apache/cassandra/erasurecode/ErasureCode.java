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
import com.backblaze.erasure.ReedSolomon;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class ErasureCode
{

    public static final int DATA_SHARDS = ECConfig.DATA_SHARDS;
    public static final int PARITY_SHARDS = ECConfig.PARITY_SHARDS ;
    public static final int TOTAL_SHARDS = ECConfig.TOTAL_SHARDS ;
    private static final Logger logger = LoggerFactory.getLogger(ErasureCode.class);

    public  byte [] []  MyEncode (String inputFile) throws IOException
    {
        // Get the size of the input file.  (Files bigger that  Integer.MAX_VALUE will fail here!)

        final int fileSize = (int) inputFile.length();
        // Figure out how big each shard will be.  .
        final int storedSize = fileSize ; // + BYTES_IN_INT;
        final int shardSize = (storedSize + DATA_SHARDS - 1) / DATA_SHARDS;

        // Create a buffer holding the file size, followed by
        // the contents of the file.
        final int bufferSize = shardSize * DATA_SHARDS;
        final byte [] allBytes = new byte[bufferSize];
        //ByteBuffer.wrap(allBytes).putInt(fileSize);
        //InputStream in = new FileInputStream(inputFile);
        InputStream in = new ByteArrayInputStream( inputFile.getBytes(StandardCharsets.UTF_8));
        // int bytesRead = in.read(allBytes, BYTES_IN_INT, fileSize);
        int bytesRead =0;

        try {
            bytesRead = in.read(allBytes, 0, fileSize);
        }  catch (IOException e) {
            e.printStackTrace();
        } finally
        {
            try
            {
                in.close();
            }
            catch (IOException e)
            {
                e.printStackTrace();
            }
        }
        if (bytesRead != fileSize) {
            throw new IOException("not enough bytes read");
        }

        // Make the buffers to hold the shards.
        byte [] [] shards = new byte [TOTAL_SHARDS] [shardSize];

        // Fill in the data shards
        for (int i = 0; i < DATA_SHARDS; i++) {
            System.arraycopy(allBytes, i * shardSize, shards[i], 0, shardSize);
        }
        //  for (int i = 0; i < TOTAL_SHARDS; i++) {
        //     System.out.println("before encode" + new String( shards[i], StandardCharsets.UTF_8));
        //}
        // Use Reed-Solomon to calculate the parity.
        ReedSolomon reedSolomon = ReedSolomon.create(DATA_SHARDS, PARITY_SHARDS);
        reedSolomon.encodeParity(shards, 0, shardSize);

        return shards;
    }

    public  byte []  MyDecode (byte[][] shards,boolean [] shardPresent,int shardSize ) throws IOException
    {

        ReedSolomon reedSolomon = ReedSolomon.create(DATA_SHARDS, PARITY_SHARDS);


        reedSolomon.decodeMissing(shards, shardPresent, 0, shardSize);


        byte [] NewallBytes = new byte [shardSize * DATA_SHARDS];

        long startTime = System.nanoTime();

        for (int i = 0; i < DATA_SHARDS; i++) {
            System.arraycopy(shards[i], 0, NewallBytes, shardSize * i, shardSize);
        }
        long stopTime = System.nanoTime();
        System.out.println((stopTime - startTime)/1000);

        return NewallBytes;
        // //return new String( NewallBytes, StandardCharsets.UTF_8).trim();
    }
}