package org.techbd.service.http.hub.prime.ux;

import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@Controller
public class MavenController implements WebMvcConfigurer {

    private static final String MAVEN_SITE_DIRECTORY = "target/site";

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/maven-site/**")
                .addResourceLocations("file:" + MAVEN_SITE_DIRECTORY + "/");
    }

    @GetMapping("/site")
    public ModelAndView site() {
        ModelAndView modelAndView = new ModelAndView();
        modelAndView.setViewName("redirect:/maven-site/index.html");
        return modelAndView;
    }
}
