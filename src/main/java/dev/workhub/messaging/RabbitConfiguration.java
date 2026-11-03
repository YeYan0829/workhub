package dev.workhub.messaging;

import org.springframework.amqp.core.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "workhub.messaging.enabled", havingValue = "true")
public class RabbitConfiguration {
    public static final String EXCHANGE = "workhub.work";
    public static final String QUEUE = "knowledge.index";
    public static final String PARKING = "work.failed";

    @Bean
    Declarables workTopology() {
        var exchange = new DirectExchange(EXCHANGE, true, false);
        var work = new Queue(QUEUE, true, false, false);
        var github = new Queue("github.work", true, false, false);
        var business = new Queue("business.work", true, false, false);
        var embedding = new Queue("knowledge.embedding", true, false, false);
        var agent = new Queue("agent.read", true, false, false);
        var parking = new Queue(PARKING, true, false, false);
        return new Declarables(
                exchange,
                agent,
                BindingBuilder.bind(agent).to(exchange).with("agent.read"),
                embedding,
                BindingBuilder.bind(embedding).to(exchange).with("knowledge.embedding"),
                work,
                github,
                business,
                BindingBuilder.bind(business).to(exchange).with("business.work"),
                BindingBuilder.bind(github).to(exchange).with("github.work"),
                parking,
                BindingBuilder.bind(work).to(exchange).with(QUEUE),
                BindingBuilder.bind(parking).to(exchange).with(PARKING));
    }
}
