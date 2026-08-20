package com.spartan.dms.config;

import org.modelmapper.ModelMapper;
import org.modelmapper.convention.MatchingStrategies;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ModelMapperConfig {

    @Bean
    public ModelMapper modelMapper() {
        ModelMapper modelMapper = new ModelMapper();

        // STRICT matching (instead of the default STANDARD) requires every
        // source property to unambiguously match a destination property by
        // full name/type before ModelMapper will wire it up. This removes
        // any possibility of implicit/"loose" guessing accidentally mapping
        // an unrelated field onto a sensitive field such as `id`.
        modelMapper.getConfiguration()
                .setMatchingStrategy(MatchingStrategies.STRICT)
                .setFieldMatchingEnabled(true)
                .setSkipNullEnabled(true);

        return modelMapper;
    }
}