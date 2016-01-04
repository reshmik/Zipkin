package org.springframework.cloud.sleuth.instrument.integration;

import static org.assertj.core.api.Assertions.registerCustomDateFormat;
import static org.assertj.core.api.BDDAssertions.then;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertEquals;

import org.assertj.core.api.BDDAssertions;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.IntegrationTest;
import org.springframework.boot.test.SpringApplicationConfiguration;
import org.springframework.cloud.sleuth.Trace;
import org.springframework.cloud.sleuth.TraceManager;
import org.springframework.cloud.sleuth.instrument.integration.TraceStompMessageChannelInterceptorTests.TestApplication;
import org.springframework.cloud.sleuth.sampler.AlwaysSampler;
import org.springframework.cloud.sleuth.trace.TraceContextHolder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageHandler;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.support.ExecutorSubscribableChannel;
import org.springframework.messaging.support.GenericMessage;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;

/**
 * 
 * @author Gaurav Rai Mazra
 *
 */
@RunWith(SpringJUnit4ClassRunner.class)
@SpringApplicationConfiguration(classes = TestApplication.class)
@IntegrationTest
public class TraceStompMessageChannelInterceptorTests {
	@Autowired
	@Qualifier("executorSubscribableChannel")
	private ExecutorSubscribableChannel channel;

	@Autowired TraceManager traceManager;
	@Autowired StompMessageHandler stompMessageHandler;
	
	@Before
	public void init() {
		this.channel.subscribe(stompMessageHandler);
	}

	@After
	public void close() {
		TraceContextHolder.removeCurrentTrace();
		this.channel.unsubscribe(stompMessageHandler);
	}

	@Test
	public void should_not_create_span_if_message_contains_not_sampled_header() {
		Message<?> message = givenMessageNotToBeSampled();

		whenTheMessageWasSent(message);

		thenSpanIdFromHeadersIsEmpty();
		thenReceivedMessageIsEqualToTheSentOne(message);
	}

	@Test
	public void should_create_span_when_headers_dont_contain_not_sampled() {
		Message<?> message = givenMessageToBeSampled();

		whenTheMessageWasSent(message);

		thenSpanIdFromHeadersIsNotEmpty();
		thenTraceIdFromHeadersIsNotEmpty();
		then(TraceContextHolder.getCurrentTrace()).isNull();
	}

	@Test
	public void should_propagate_headers_when_message_was_sent_during_local_span_starting() {
		Trace trace = givenALocallyStartedSpan();
		Message<?> message = givenMessageToBeSampled();

		whenTheMessageWasSent(message);
		traceManager.close(trace);

		String spanId = thenSpanIdFromHeadersIsNotEmpty();
		String traceId = thenTraceIdFromHeadersIsNotEmpty();
		then(traceId).isEqualTo(trace.getSpan().getTraceId());
		then(spanId).isEqualTo(trace.getSpan().getSpanId());
		then(TraceContextHolder.getCurrentTrace()).isNull();
	}

	private Trace givenALocallyStartedSpan() {
		return traceManager.startSpan("testSendMessage", new AlwaysSampler(), null);
	}

	private Message<?> givenMessageNotToBeSampled() {
		return StompMessageBuilder.fromMessage(new GenericMessage<>("Message2")).setHeader(Trace.NOT_SAMPLED_NAME, "").build();
	}

	private Message<?> givenMessageToBeSampled() {
		return StompMessageBuilder.fromMessage(new GenericMessage<>("Message2")).build();
	}

	private void whenTheMessageWasSent(Message<?> message) {
		this.channel.send(message);
		then(stompMessageHandler.message).isNotNull();
	}

	private String thenSpanIdFromHeadersIsEmpty() {
		String header = getValueFromHeaders(Trace.SPAN_ID_NAME);
		then(header).as("Span id should be empty").isNullOrEmpty();
		return header;
	}

	private String thenSpanIdFromHeadersIsNotEmpty() {
		String header = getValueFromHeaders(Trace.SPAN_ID_NAME);
		then(header).as("Span id should not be empty").isNotEmpty();
		return header;
	}

	private String thenTraceIdFromHeadersIsNotEmpty() {
		String header = getValueFromHeaders(Trace.TRACE_ID_NAME);
		then(header).as("Trace id should not be empty").isNotEmpty();
		return header;
	}

	private void thenReceivedMessageIsEqualToTheSentOne(Message<?> message) {
		then(message.getPayload()).isEqualTo(stompMessageHandler.message.getPayload());
	}

	private String getValueFromHeaders(String headerName) {
		return stompMessageHandler.message.getHeaders().get(headerName, String.class);
	}

	@Configuration
	@EnableAutoConfiguration
	static class TestApplication {

		@Bean ExecutorSubscribableChannel executorSubscribableChannel(TraceStompMessageChannelInterceptor stompChannelInterceptor) {
			ExecutorSubscribableChannel channel = new ExecutorSubscribableChannel();
			channel.addInterceptor(stompChannelInterceptor);
			return channel;
		}

		@Bean StompMessageHandler stompMessageHandler() {
			return new StompMessageHandler();
		}

		@Bean AlwaysSampler alwaysSampler() {
			return new AlwaysSampler();
		}
	}

	static class StompMessageHandler implements MessageHandler {

		Message<?> message;

		@Override
		public void handleMessage(Message<?> message) throws MessagingException {
			this.message = message;
		}
	}
}