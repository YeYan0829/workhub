package w4fixture;

import dev.workhub.WorkHubApplication;
import dev.workhub.agent.AgentSettings;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Isolated browser fault fixture only. Never packaged in the production jar. */
public class NonzeroBudgetApplication {
  public static void main(String[] args) {
    new SpringApplicationBuilder(WorkHubApplication.class, Prices.class).run(args);
  }
  @Configuration(proxyBeanMethods=false)
  static class Prices {
    @Bean static BeanPostProcessor syntheticPrices() {
      return new BeanPostProcessor() {
        public Object postProcessAfterInitialization(Object bean,String name) {
          if(bean instanceof AgentSettings settings) {
            if(!settings.fixture || !settings.authorization.startsWith("w4-"))
              throw new IllegalStateException("Synthetic budget fixture requires a local model and W4 authorization");
            try {
              // Real reservation path, nonzero virtual prices, one nanoUSD ceiling.
              // Every model reservation must fail, before any model HTTP request.
              for(var entry:java.util.Map.of("inputPrice",2L,"outputPrice",5L,"budget",1L).entrySet()) {
                var field=AgentSettings.class.getDeclaredField(entry.getKey());
                field.setAccessible(true);field.setLong(settings,entry.getValue());
              }
            }catch(ReflectiveOperationException e){throw new IllegalStateException(e);}
          }
          return bean;
        }
      };
    }
  }
}
