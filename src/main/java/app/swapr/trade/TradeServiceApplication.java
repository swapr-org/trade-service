package app.swapr.trade;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
class TradeServiceApplication {

  public static void main(String[] args) {
    SpringApplication.run(TradeServiceApplication.class, args);
  }
}
