package com.lowtuna.jsonblob;

import com.codahale.metrics.MetricRegistry;
import com.github.jknack.handlebars.Handlebars;
import com.lowtuna.dropwizard.extras.heroku.RequestIdFilter;
import com.lowtuna.dropwizard.extras.view.handlebars.ConfiguredHandlebarsViewBundle;
import com.lowtuna.jsonblob.config.JsonBlobConfiguration;
import com.lowtuna.jsonblob.core.BlobManager;
import com.lowtuna.jsonblob.healthcheck.MongoHealthCheck;
import com.lowtuna.jsonblob.resource.ApiResource;
import com.lowtuna.jsonblob.resource.JsonBlobEditorResource;
import com.lowtuna.jsonblob.util.mongo.JacksonMongoDbModule;
import com.mongodb.DB;
import io.dropwizard.Application;
import io.dropwizard.assets.AssetsBundle;
import io.dropwizard.setup.Bootstrap;
import io.dropwizard.setup.Environment;
import lombok.extern.slf4j.Slf4j;
import org.joda.time.format.PeriodFormatter;
import org.joda.time.format.PeriodFormatterBuilder;

@Slf4j
public class JsonBlobApplication extends Application<JsonBlobConfiguration> {

    private final long startTIme = System.currentTimeMillis();
    private final PeriodFormatter uptimePeriodFormatter = new PeriodFormatterBuilder()
            .appendDays()
            .appendSuffix("d")
            .appendHours()
            .appendSuffix("h")
            .appendMinutes()
            .appendSuffix("m")
            .appendSeconds()
            .appendSuffix("s")
            .toFormatter();

    public static void main(String[] args) throws Exception {
        if (args.length >= 2 && args[1].startsWith("~")) {
            args[1] = System.getProperty("user.home") + args[1].substring(1);
        }
        new JsonBlobApplication().run(args);
    }

    @Override
    public String getName() {
        return "jsonblob";
    }

    @Override
    public void initialize(Bootstrap<JsonBlobConfiguration> bootstrap) {
        bootstrap.addBundle(new AssetsBundle());

        bootstrap.addBundle(new ConfiguredHandlebarsViewBundle<JsonBlobConfiguration>() {
            @Override
            public Handlebars getInstance(JsonBlobConfiguration configuration) {
                log.info("Using Handlebars configuration of {}", configuration.getHandlebarsConfig().getClass().getCanonicalName());
                return configuration.getHandlebarsConfig().getInstance();
            }
        });
    }

    @Override
    public void run(JsonBlobConfiguration configuration, Environment environment) throws ClassNotFoundException {
        environment.metrics().register(MetricRegistry.name(getClass(), "uptime"), new com.codahale.metrics.Gauge<String>() {
            @Override
            public String getValue() {
                return uptimePeriodFormatter.print(new org.joda.time.Duration(System.currentTimeMillis() - startTIme).toPeriod());
            }
        });

        environment.getObjectMapper().registerModule(new JacksonMongoDbModule());

        DB mongoDBInstance = configuration.getMongoDbConfig().instance();

        BlobManager blobManager = new BlobManager(
                mongoDBInstance,
                configuration.getBlobManagerConfig().getBlobCollectionName(),
                configuration.getBlobManagerConfig().getScheduledExecutorService().instance(environment),
                configuration.getBlobManagerConfig().getBlobCleanupFrequency(),
                configuration.getBlobManagerConfig().getBlobAccessTtl(),
                environment.metrics());
        environment.lifecycle().manage(blobManager);

        environment.healthChecks().register("MongoDB", new MongoHealthCheck(mongoDBInstance));

        environment.jersey().register(new ApiResource(blobManager, configuration.getBlobManagerConfig().isDeleteEnabled(), configuration.getGoogleAnalyticsConfig()));
        environment.jersey().register(new JsonBlobEditorResource(blobManager, configuration.getGoogleAnalyticsConfig()));
        environment.jersey().register(RequestIdFilter.class);
    }

}