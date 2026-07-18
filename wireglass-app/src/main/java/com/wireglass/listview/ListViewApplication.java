package com.wireglass.listview;

import com.wireglass.listview.config.ListViewProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class ListViewApplication {

    public static void main(String[] args) {
        SpringApplication.run(ListViewApplication.class, args);
    }
}
