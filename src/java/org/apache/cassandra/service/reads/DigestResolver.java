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
package org.apache.cassandra.service.reads;

import java.nio.ByteBuffer;
import java.util.Collection;
import java.util.concurrent.TimeUnit;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;

import org.apache.cassandra.db.DecoratedKey;
import org.apache.cassandra.db.ReadCommand;
import org.apache.cassandra.db.ReadResponse;
import org.apache.cassandra.db.RegularAndStaticColumns;
import org.apache.cassandra.db.SinglePartitionReadCommand;
import org.apache.cassandra.db.partitions.PartitionIterator;
import org.apache.cassandra.db.partitions.PartitionUpdate;
import org.apache.cassandra.db.partitions.SingletonUnfilteredPartitionIterator;
import org.apache.cassandra.db.partitions.UnfilteredPartitionIterators;
import org.apache.cassandra.db.rows.BTreeRow;
import org.apache.cassandra.db.rows.Cell;
import org.apache.cassandra.db.rows.Row;
import org.apache.cassandra.db.rows.RowIterator;
import org.apache.cassandra.db.rows.UnfilteredRowIterator;
import org.apache.cassandra.erasurecode.ECConfig;
import org.apache.cassandra.erasurecode.ECResponse;
import org.apache.cassandra.erasurecode.ErasureCode;
import org.apache.cassandra.locator.Endpoints;
import org.apache.cassandra.locator.InetAddressAndPort;
import org.apache.cassandra.locator.Replica;
import org.apache.cassandra.locator.ReplicaPlan;
import org.apache.cassandra.net.Message;
import org.apache.cassandra.schema.ColumnMetadata;
import org.apache.cassandra.schema.TableMetadata;
import org.apache.cassandra.service.reads.repair.NoopReadRepair;
import org.apache.cassandra.tracing.Tracing;
import org.apache.cassandra.transport.Dispatcher;
import org.apache.cassandra.utils.ByteBufferUtil;

import static com.google.common.collect.Iterables.any;
import static org.apache.cassandra.utils.Clock.Global.nanoTime;

public class DigestResolver<E extends Endpoints<E>, P extends ReplicaPlan.ForRead<E, P>> extends ResponseResolver<E, P>
{
    private volatile Message<ReadResponse> dataResponse;

    public DigestResolver(ReadCommand command, ReplicaPlan.Shared<E, P> replicaPlan, Dispatcher.RequestTime requestTime)
    {
        super(command, replicaPlan, requestTime);
        Preconditions.checkArgument(command instanceof SinglePartitionReadCommand,
                                    "DigestResolver can only be used with SinglePartitionReadCommand commands");
    }

    @Override
    public void preprocess(Message<ReadResponse> message)
    {
        super.preprocess(message);
        Replica replica = replicaPlan().lookup(message.from());
        if (dataResponse == null && !message.payload.isDigestResponse() && replica.isFull())
            dataResponse = message;
    }

    @VisibleForTesting
    public boolean hasTransientResponse()
    {
        return hasTransientResponse(responses.snapshot());
    }

    private boolean hasTransientResponse(Collection<Message<ReadResponse>> responses)
    {
        return any(responses,
                msg -> !msg.payload.isDigestResponse()
                        && replicaPlan().lookup(msg.from()).isTransient());
    }


    public PartitionIterator getData()
    {
        Collection<Message<ReadResponse>> responses = this.responses.snapshot();

        if (!hasTransientResponse(responses))
        {
            return UnfilteredPartitionIterators.filter(dataResponse.payload.makeIterator(command), command.nowInSec());
        }
        else
        {
            // This path can be triggered only if we've got responses from full replicas and they match, but
            // transient replica response still contains data, which needs to be reconciled.
            DataResolver<E, P> dataResolver
                    = new DataResolver<>(command, replicaPlan, NoopReadRepair.instance, requestTime);

            dataResolver.preprocess(dataResponse);
            // Reconcile with transient replicas
            for (Message<ReadResponse> response : responses)
            {
                Replica replica = replicaPlan().lookup(response.from());
                if (replica.isTransient())
                    dataResolver.preprocess(response);
            }

            return dataResolver.resolve();
        }
    }

    public ReadResponse modifyCellValue(ReadResponse originalResponse,String encoded_value) {
        // Extract the data from the original response
        PartitionIterator partitions = UnfilteredPartitionIterators.filter(originalResponse.makeIterator(command), command.nowInSec());
        // Create a new PartitionIterator to hold the modified data

        // Use a builder to collect the modified partitions
        ReadResponse resp = null;


        while (partitions.hasNext()) {
            try (RowIterator rows = partitions.next()) {
                TableMetadata tableMetadata  = rows.metadata();
                DecoratedKey partitionKey = rows.partitionKey();
                RegularAndStaticColumns clm = rows.columns();

                PartitionUpdate.Builder builder =  new PartitionUpdate.Builder(tableMetadata, partitionKey,clm,1,false);

                // Traverse rows and modify the target cell
                while (rows.hasNext()) {
                    Row row = rows.next();
                    Row.Builder rowBuilder = BTreeRow.sortedBuilder();

                    // Copy clustering
                    rowBuilder.newRow(row.clustering());

                    // Traverse cells
                    for (Cell<?> cell : row.cells()) {
                        if (cell.column().name.toString().equals(ECConfig.EC_COLUMN)) {
                            // Modify target cell
                            rowBuilder.addCell(cell.withUpdatedValue(ByteBufferUtil.bytes(encoded_value)));
                        } else {
                            // Copy existing cells
                            rowBuilder.addCell(cell);
                        }
                    }

                    // Add modified row to partition builder
                    builder.add(rowBuilder.build());
                }
                PartitionUpdate partitionUpdate = builder.build();
                UnfilteredRowIterator rowIterator = partitionUpdate.unfilteredIterator();
                resp = ReadResponse.createSimpleDataResponse(new SingletonUnfilteredPartitionIterator(rowIterator), command.columnFilter());

            }
        }

        // Create a new ReadResponse with modified data
        return resp;
    }

    public boolean isMyRead()
    {
        ColumnMetadata tagMetadata  = command.metadata().getColumn(ByteBufferUtil.bytes(ECConfig.EC_COLUMN));
        //logger.info("Raj Read for keyspace: "+ command.metadata().keyspace);
        boolean isMyRead = (tagMetadata != null);
        return isMyRead;
    }

        public PartitionIterator myCombineResponse()
    {
        // array to keep track which code part is available
        boolean []  isCodeavailable = new boolean[ECConfig.TOTAL_SHARDS];
        //boolean IswholeValue = false;
        ReadResponse tmp = null;
        Collection<Message<ReadResponse>> snapshot = responses.snapshot();

        if( snapshot.size() < ECConfig.DATA_SHARDS )
        {
            Tracing.trace("Only got {} responses:{} , needed {}",snapshot.size(),ECConfig.DATA_SHARDS);
        }
        //logger.error("Got responses total "+snapshot.size());
        ECResponse[] ecResponses = new ECResponse[ECConfig.TOTAL_SHARDS];//new ECResponse[snapshot.size()];
        for(int i=0;i<ECConfig.TOTAL_SHARDS;i++)
        {
            ecResponses[i] = new ECResponse();
        }
        int ShardSize =-1;
        for (Message<ReadResponse> message : snapshot)
        {
            //String messageSender = message.from().getHostAddress(false);
            //int ECIndexOfServer  = ECConfig.getAddressMap().get(messageSender);
            
            //ecResponses[TmpIndex].setEcCodeIndex(ECIndexOfServer);
            ReadResponse response = message.payload;


            // check if the response is indeed a data response
            // we shouldn't get a digest response here

            //assert response.isDigestResponse() == false;
            if(response.isDigestResponse() == true)
            {
                logger.error("digest received");
                continue;
            }

            tmp = message.payload;
            // get the partition iterator corresponding to the
            // current data response
            PartitionIterator pi = UnfilteredPartitionIterators.filter(response.makeIterator(command), command.nowInSec());

            // get the z value column
            while(pi.hasNext())
            {
                // pi.next() returns a RowIterator
                RowIterator ri = pi.next();

                while(ri.hasNext())
                {
                    // todo: the entire row is read for the sake of development
                    // future improvement could be made
                        ColumnMetadata colMeta = command.metadata().getColumn(ByteBufferUtil.bytes(ECConfig.EC_COLUMN));
                    try
                    {
                        Cell c = ri.next().getCell(colMeta); // ri.next() = Row
                        ByteBuffer Finalbuffer = c.buffer();

                        byte isEc = Finalbuffer.get();

                        //if isEc !=1  not necessarly whole value, some "signal string from a new node just boot up,
                        // cassandra send last message to bootup node , which is signal string ?"

                        if( isEc !=1)
                        {
                            Finalbuffer.position(0);
                            if(isEc == 0) // whole value
                            {
                                Finalbuffer.position(1);
                                //logger.info("Whole value found len " + Finalbuffer.remaining()+" TIMESTAMP"+ c.timestamp() +"count" + ECConfig.wholeValueFound++ +"from"+message.from().getHostAddress(false));
                                ReadResponse tmpp = modifyCellValue(tmp,ByteBufferUtil.string(Finalbuffer));// should use trim() mostly Yes?
                                return UnfilteredPartitionIterators.filter(tmpp.makeIterator(command), command.nowInSec());

                            }
                            else if ("signal".equals(ByteBufferUtil.string(Finalbuffer).substring(0, Math.min(ByteBufferUtil.string(Finalbuffer).length(), 6))))
                            {
                                // garbage value consider lost
                                logger.info("Garbage value discarding/ read response, len "+Finalbuffer.remaining() + "tid:"+Thread.currentThread().getId());
                                continue;
                            }
                            else// this should never happen
                            {
                                assert true == false;
                            }
                        }
                        // do not comment below , need to remove from finalbuffer
                        // to move pointer forward
                        int n = Finalbuffer.getInt();
                        int k = Finalbuffer.getInt();
                        int codeIndex = Finalbuffer.getInt();
                        int coded_valueLength = Finalbuffer.getInt();
                        ShardSize = coded_valueLength;

                        if(codeIndex < ECConfig.DATA_SHARDS) // data shard
                        {

                           // logger.error("data shard found TIMESTAMP "+ c.timestamp() + " index #"+codeIndex+ "shardSize"+ShardSize +" from "+ message.from().getHostAddress(false));
                            //String value = ByteBufferUtil.string(Finalbuffer);
                            //ecResponses[codeIndex].setEcCode(value);
                            //ecResponses[codeIndex].setCodeLength(value.length());
                            ecResponses[codeIndex].setEcCode(Finalbuffer.slice().duplicate());

                        }
                        else // parity shard
                        {
                           // logger.error("parity shard found TIMESTAMP "+ c.timestamp()+" index #"+codeIndex+ "shardSize"+ShardSize+ "from "+ message.from().getHostAddress(false));
                            ecResponses[codeIndex].setEcCodeParity(Finalbuffer.slice().duplicate());


                        }
                        ecResponses[codeIndex].setCodeLength(coded_valueLength);
                        ecResponses[codeIndex].setIsEcCoded(isEc);
                        ecResponses[codeIndex].setCodeTimestamp(c.timestamp());
                        ecResponses[codeIndex].setCodeAvailable(true);
                        ecResponses[codeIndex].setEcCodeIndex(codeIndex);
                        isCodeavailable[codeIndex] = true;
                    }
                    catch (Exception e)
                    {
                        e.printStackTrace();
                        return null;
                    }
                }
            }
        }


        // verify if decoding needed
        // no decodign needed if all data fragment present, just combine and return
        // no decoding needed/possible if whole data presend or not enough codes available


        // check if all "DATA" codes available
        boolean IsEcDeccodeNeeded = false;

        for (int i=0;i<ECConfig.DATA_SHARDS;i++)
        {
            if(!isCodeavailable[i])
            {
                IsEcDeccodeNeeded = true;
            }

        }

        try
        {
            if(!IsEcDeccodeNeeded)
            {
                // just combine and return
                //logger.info("Read Returning: All data shartd available ");
                String encoded_value = "";
                for (int i = 0; i < ECConfig.DATA_SHARDS; i++) {

                    encoded_value = encoded_value + ByteBufferUtil.string(ecResponses[i].getEcCodeParity()); //ecResponses[i].getEcCode();

                    //logger.info("Combining value:  " + encoded_value);
                }
                //logger.info("No decoding needed Combining value:  " + encoded_value);
                ReadResponse tmpp = modifyCellValue(tmp,encoded_value.trim());// should use trim() mostly Yes?
                return UnfilteredPartitionIterators.filter(tmpp.makeIterator(command), command.nowInSec());
            }
        }
        catch (Exception e)
        {
            e.printStackTrace();
            return null;
        }

        //assert true == false;


        // decode and combine values
        //long decodeStart = nanoTime();
        //logger.info("shard size" +ShardSize+ " tid:"+Thread.currentThread().getId() );
        byte[][] decodeMatrix = new byte[ECConfig.TOTAL_SHARDS][ShardSize];

        for (int i = 0; i < ecResponses.length; i++) {

            if(ecResponses[i].getIsCodeAvailable())  // code available, set corresponding index
            {
                ByteBuffer value = ecResponses[i].getEcCodeParity();
                value.get (decodeMatrix[ecResponses[i].getEcCodeIndex()]) ;// .getBytes(StandardCharsets.UTF_8);
                //logger.info("decoding length of index "+ ecResponses[i].getEcCodeIndex() + " is " + decodeMatrix[ecResponses[i].getEcCodeIndex()].length);
            }
            else // code not available , allocate empty space
            {
                decodeMatrix[i] = new byte[ShardSize];
            }
        }

        try
        {
            String encoded_value = new ErasureCode().MyDecode(decodeMatrix, isCodeavailable, ShardSize, ECConfig.TOTAL_SHARDS,ECConfig.DATA_SHARDS );

            //ECConfig.DecodingNeeded++;
            //ECConfig.myWriter.println("Decoding#: "+ECConfig.DecodingNeeded + "time ms ,"+TimeUnit.NANOSECONDS.toMillis(nanoTime() - decodeStart));

            //String encoded_value = "testValue";
            // encoded_value = codelist.get(0) + codelist.get(1) ;
            //Tracing.trace("Read Returning: encoded value is {}",encoded_value);
           // logger.info("Read Returning: encoded main computer value is "+ encoded_value);

            ReadResponse tmpp = modifyCellValue(tmp,encoded_value);
            return UnfilteredPartitionIterators.filter(tmpp.makeIterator(command), command.nowInSec());
        }
        catch (Exception e)
        {
            throw new RuntimeException(e);
        }



    }

    public boolean responsesMatch()
    {
        long start = nanoTime();

        // validate digests against each other; return false immediately on mismatch.
        ByteBuffer digest = null;
        Collection<Message<ReadResponse>> snapshot = responses.snapshot();
        assert snapshot.size() > 0 : "Attempted response match comparison while no responses have been received.";
        if (snapshot.size() == 1)
            return true;

        // TODO: should also not calculate if only one full node
        for (Message<ReadResponse> message : snapshot)
        {
            if (replicaPlan().lookup(message.from()).isTransient())
                continue;

            ByteBuffer newDigest = message.payload.digest(command);
            if (digest == null)
                digest = newDigest;
            else if (!digest.equals(newDigest))
                // rely on the fact that only single partition queries use digests
                return false;
        }

        if (logger.isTraceEnabled())
            logger.trace("responsesMatch: {} ms.", TimeUnit.NANOSECONDS.toMillis(nanoTime() - start));

        return true;
    }

    public boolean isDataPresent()
    {
        return dataResponse != null;
    }

    public DigestResolverDebugResult[] getDigestsByEndpoint()
    {
        DigestResolverDebugResult[] ret = new DigestResolverDebugResult[responses.size()];
        for (int i = 0; i < responses.size(); i++)
        {
            Message<ReadResponse> message = responses.get(i);
            ReadResponse response = message.payload;
            String digestHex = ByteBufferUtil.bytesToHex(response.digest(command));
            ret[i] = new DigestResolverDebugResult(message.from(), digestHex, message.payload.isDigestResponse());
        }
        return ret;
    }

    public static class DigestResolverDebugResult
    {
        public InetAddressAndPort from;
        public String digestHex;
        public boolean isDigestResponse;

        private DigestResolverDebugResult(InetAddressAndPort from, String digestHex, boolean isDigestResponse)
        {
            this.from = from;
            this.digestHex = digestHex;
            this.isDigestResponse = isDigestResponse;
        }
    }
}
