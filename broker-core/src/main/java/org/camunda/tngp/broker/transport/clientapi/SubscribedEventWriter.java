package org.camunda.tngp.broker.transport.clientapi;

import static org.camunda.tngp.protocol.clientapi.SubscribedEventEncoder.eventHeaderLength;
import static org.camunda.tngp.protocol.clientapi.SubscribedEventEncoder.keyNullValue;
import static org.camunda.tngp.protocol.clientapi.SubscribedEventEncoder.partitionIdNullValue;
import static org.camunda.tngp.protocol.clientapi.SubscribedEventEncoder.positionNullValue;
import static org.camunda.tngp.protocol.clientapi.SubscribedEventEncoder.subscriberKeyNullValue;
import static org.camunda.tngp.protocol.clientapi.SubscribedEventEncoder.topicNameHeaderLength;

import java.util.Objects;

import org.agrona.DirectBuffer;
import org.agrona.MutableDirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;
import org.camunda.tngp.protocol.Protocol;
import org.camunda.tngp.protocol.clientapi.EventType;
import org.camunda.tngp.protocol.clientapi.MessageHeaderEncoder;
import org.camunda.tngp.protocol.clientapi.SubscribedEventEncoder;
import org.camunda.tngp.protocol.clientapi.SubscriptionType;
import org.camunda.tngp.util.buffer.BufferWriter;
import org.camunda.tngp.util.buffer.DirectBufferWriter;

public class SubscribedEventWriter implements BufferWriter
{
    protected final MessageHeaderEncoder headerEncoder = new MessageHeaderEncoder();
    protected final SubscribedEventEncoder bodyEncoder = new SubscribedEventEncoder();

    protected int channelId;

    protected DirectBuffer topicName = new UnsafeBuffer(0, 0);
    protected int partitionId = partitionIdNullValue();
    protected long position = positionNullValue();
    protected long key = keyNullValue();
    protected long subscriberKey = subscriberKeyNullValue();
    protected SubscriptionType subscriptionType;
    protected EventType eventType;
    protected DirectBufferWriter eventBuffer = new DirectBufferWriter();
    protected BufferWriter eventWriter;

    protected final SingleMessageWriter singleMessageWriter;

    public SubscribedEventWriter(final SingleMessageWriter singleMessageWriter)
    {
        this.singleMessageWriter = singleMessageWriter;
    }

    public SubscribedEventWriter channelId(final int channelId)
    {
        this.channelId = channelId;
        return this;
    }

    public SubscribedEventWriter topicName(final DirectBuffer topicName)
    {
        this.topicName.wrap(topicName);
        return this;
    }

    public SubscribedEventWriter partitionId(final int partitionId)
    {
        this.partitionId = partitionId;
        return this;
    }

    public SubscribedEventWriter position(final long position)
    {
        this.position = position;
        return this;
    }

    public SubscribedEventWriter key(final long key)
    {
        this.key = key;
        return this;
    }

    public SubscribedEventWriter subscriberKey(final long subscriberKey)
    {
        this.subscriberKey = subscriberKey;
        return this;
    }

    public SubscribedEventWriter subscriptionType(final SubscriptionType subscriptionType)
    {
        this.subscriptionType = subscriptionType;
        return this;
    }

    public SubscribedEventWriter eventType(final EventType eventType)
    {
        this.eventType = eventType;
        return this;
    }

    public SubscribedEventWriter event(final DirectBuffer buffer, final int offset, final int length)
    {
        this.eventBuffer.wrap(buffer, offset, length);
        this.eventWriter = eventBuffer;
        return this;
    }

    public SubscribedEventWriter eventWriter(final BufferWriter eventWriter)
    {
        this.eventWriter = eventWriter;
        return this;
    }

    @Override
    public int getLength()
    {
        return MessageHeaderEncoder.ENCODED_LENGTH +
                SubscribedEventEncoder.BLOCK_LENGTH +
                topicNameHeaderLength() +
                topicName.capacity() +
                eventHeaderLength() +
                eventWriter.getLength();
    }

    @Override
    public void write(final MutableDirectBuffer buffer, int offset)
    {
        headerEncoder
            .wrap(buffer, offset)
            .blockLength(bodyEncoder.sbeBlockLength())
            .templateId(bodyEncoder.sbeTemplateId())
            .schemaId(bodyEncoder.sbeSchemaId())
            .version(bodyEncoder.sbeSchemaVersion());

        offset += MessageHeaderEncoder.ENCODED_LENGTH;

        bodyEncoder
            .wrap(buffer, offset)
            .partitionId(partitionId)
            .putTopicName(topicName, 0, topicName.capacity())
            .position(position)
            .key(key)
            .subscriberKey(subscriberKey)
            .subscriptionType(subscriptionType)
            .eventType(eventType);

        offset += SubscribedEventEncoder.BLOCK_LENGTH + topicNameHeaderLength() + topicName.capacity();

        final int eventLength = eventWriter.getLength();
        buffer.putShort(offset, (short) eventLength, Protocol.ENDIANNESS);

        offset += eventHeaderLength();
        eventWriter.write(buffer, offset);
    }

    public boolean tryWriteMessage()
    {
        Objects.requireNonNull(eventWriter);

        try
        {
            return singleMessageWriter.tryWrite(channelId, this);
        }
        finally
        {
            reset();
        }
    }

    protected void reset()
    {
        this.channelId = -1;
        this.partitionId = partitionIdNullValue();
        this.topicName.wrap(0, 0);
        this.position = positionNullValue();
        this.key = keyNullValue();
        this.subscriberKey = subscriberKeyNullValue();
        this.subscriptionType = SubscriptionType.NULL_VAL;
        this.eventType = EventType.NULL_VAL;
        this.eventWriter = null;
    }
}
