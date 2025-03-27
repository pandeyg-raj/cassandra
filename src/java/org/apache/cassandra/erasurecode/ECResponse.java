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

import java.nio.ByteBuffer;

public class ECResponse
{
    private String ecCode ; //= new String[ECConfig.TOTAL_SHARDS];

    public ECResponse()
    {
        this.IsCodeAvailable = false;
    }

    public ByteBuffer getEcCodeParity()
    {
        return ecCodeParity;
    }

    public void setEcCodeParity(ByteBuffer ecCodeParity)
    {
        this.ecCodeParity = ecCodeParity;
    }

    private ByteBuffer ecCodeParity;
    private boolean IsCodeAvailable ;   //= new boolean[ECConfig.TOTAL_SHARDS];
    private int codeLength;
    private long codeTimestamp;
    private int ecCodeIndex;

    public int getIsEcCoded()
    {
        return IsEcCoded;
    }

    public void setIsEcCoded(int isEcCoded)
    {
        IsEcCoded = isEcCoded;
    }

    private int IsEcCoded;

    public int getEcCodeIndex()
    {
        return ecCodeIndex;
    }

    public void setEcCodeIndex(int ecCodeIndex)
    {
        this.ecCodeIndex = ecCodeIndex;
    }

    public String getEcCode()
    {
        return ecCode;
    }

    public void setEcCode(String ecCode)
    {
        this.ecCode = ecCode;
    }

    public boolean getIsCodeAvailable()
    {
        return IsCodeAvailable;
    }

    public void setCodeAvailable(boolean codeAvailable)
    {
        IsCodeAvailable = codeAvailable;
    }

    public int getCodeLength()
    {
        return codeLength;
    }

    public void setCodeLength(int codeLength)
    {
        this.codeLength = codeLength;
    }

    public long getCodeTimestamp()
    {
        return codeTimestamp;
    }

    public void setCodeTimestamp(long codeTimestamp)
    {
        this.codeTimestamp = codeTimestamp;
    }

}
