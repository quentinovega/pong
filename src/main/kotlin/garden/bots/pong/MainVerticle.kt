package garden.bots.pong

import io.vertx.core.AbstractVerticle
import io.vertx.core.Promise
import io.vertx.ext.web.Router
import io.vertx.ext.web.client.WebClient
import io.vertx.ext.web.handler.BodyHandler
import io.vertx.ext.web.handler.StaticHandler
import io.vertx.kotlin.core.json.json
import io.vertx.kotlin.core.json.jsonArrayOf
import io.vertx.kotlin.core.json.obj
import io.vertx.servicediscovery.Record
import io.vertx.servicediscovery.ServiceDiscovery
import io.vertx.servicediscovery.ServiceDiscoveryOptions
import io.vertx.servicediscovery.ServiceReference
import io.vertx.servicediscovery.rest.ServiceDiscoveryRestEndpoint
import io.vertx.servicediscovery.types.HttpEndpoint

class MainVerticle : AbstractVerticle() {

  private lateinit var discovery: ServiceDiscovery
  private lateinit var record: Record

  override fun stop(stopPromise: Promise<Void>) {
    println("Unregistration process is started (${record.registration})...")

    discovery.unpublish(record.registration) { ar ->
      when {
        ar.failed() -> {
          println("😡 Unable to unpublish the microservice: ${ar.cause().message}")
          stopPromise.fail(ar.cause())
        }
        ar.succeeded() -> {
          println("👋 bye bye ${record.registration}")
          stopPromise.complete()
        }
      }
    }
  }

  override fun start(startPromise: Promise<Void>) {

    // ===== Discovery ===
    val redisPort= System.getenv("REDIS_PORT")?.toInt() ?: 6379
    val redisHost = System.getenv("REDIS_HOST") ?: "127.0.0.1" // "redis-master.database"
    val redisAuth = System.getenv("REDIS_PASSWORD") ?: null
    val redisRecordsKey = System.getenv("REDIS_RECORDS_KEY") ?: "vert.x.ms" // the redis hash

    val serviceDiscoveryOptions = ServiceDiscoveryOptions()

    discovery = ServiceDiscovery.create(vertx,
      serviceDiscoveryOptions.setBackendConfiguration(
        json {
          obj(
            "host" to redisHost,
            "port" to redisPort,
            "auth" to redisAuth,
            "key" to redisRecordsKey
          )
        }
      ))

    // create the microservice record
    //   export HOST="${APPLICATION_NAME}.${BRANCH}.${CLUSTER_IP}.nip.io"
    val serviceName = System.getenv("SERVICE_NAME") ?: "john-doe-service"
    val serviceHost = System.getenv("SERVICE_HOST") ?: "john-doe-service.127.0.0.1.nip.io"
    val serviceExternalPort= System.getenv("SERVICE_PORT")?.toInt() ?: 80

    record = HttpEndpoint.createRecord(
      serviceName,
      serviceHost, // or internal ip
      serviceExternalPort, // exposed port (internally it's 8080)
      "/api"
    )

    // --- adding some meta data ---
    record.metadata = json {
      obj(
        "api" to jsonArrayOf("/knock-knock")
      )
    }

    val router = Router.router(vertx)
    router.route().handler(BodyHandler.create())

    // use me with other microservices
    ServiceDiscoveryRestEndpoint.create(router, discovery) // call /discovery route

    router.get("/api/knock-knock").handler { context ->

      // search ping service
      discovery.getRecord(json{obj("name" to "ping-service")}) { ar ->
        when {
          ar.failed() -> {
            println("😡 enable to find ping-service in the backend discovery")
            context.response().putHeader("content-type", "application/json;charset=UTF-8")
              .end(
                json {
                  obj("message" to "😡 enable to find ping-service in the backend discovery")
                }.encodePrettily()
              )
          } // ⬅️ failed

          ar.succeeded() -> {
            println("😀 I found ping-service in the backend discovery")

            val pingRecord: Record = ar.result()
            println(pingRecord.toJson().encodePrettily())

            val reference: ServiceReference = discovery.getReference(pingRecord)
            val pingClient: WebClient = reference.getAs(WebClient::class.java)

            println("😍 pingClient created")
            println("🖖 knock-knock")

            pingClient.get("/api/knock-knock").send { pingResponse ->
              when {
                pingResponse.failed() -> {
                  println("😡 ouch! cannot connect to ping-service: ${pingResponse.cause().message}")
                  context.response().putHeader("content-type", "application/json;charset=UTF-8")
                    .end(
                      json {
                        obj("error" to pingResponse.cause().message)
                      }.encodePrettily()
                    )
                } // ⬅️ failed

                pingResponse.succeeded() -> {
                  println("🎉")
                  println(pingResponse.result().bodyAsJsonObject().encodePrettily())

                  context.response().putHeader("content-type", "application/json;charset=UTF-8")
                    .end(
                      json {
                        obj(
                          "responseFromPong" to "🏓 ping",
                          "callingPing" to pingResponse.result().bodyAsJsonObject()
                        )
                      }.encodePrettily()
                    )
                } // ⬅️ succeed
              }
            } // pingClient.get
          } // ⬅️ succeed
        } // when
      } // discovery
    } // route

    // serve static assets
    router.route("/*").handler(StaticHandler.create().setCachingEnabled(false))

    // internal port
    val httpPort = System.getenv("PORT")?.toInt() ?: 8080

    vertx
      .createHttpServer()
      .requestHandler(router)
      .listen(httpPort) { http ->
        if (http.succeeded()) {
          println("🏓 Pong started on $httpPort")

          /* 👋 === publish the microservice record to the discovery backend === */
          discovery.publish(record) { asyncRes ->
              when {
                asyncRes.failed() -> {
                  println("😡 Not able to publish the microservice: ${asyncRes.cause().message}")
                  startPromise.fail(asyncRes.cause())
                }

                asyncRes.succeeded() -> {
                  println("😃 Microservice is published! ${asyncRes.result().registration}")
                  startPromise.complete()

                  // remove the old or duplicated registrations
                  discovery.getRecords(json{obj("name" to serviceName)}) { ar ->
                    when {
                      ar.failed() -> println("😡 ouch! cannot connect to the discovery backend: ${ar.cause().message}")
                      ar.succeeded() -> {
                        println("🖐 all $serviceName records:")
                        println(jsonArrayOf(ar.result()).encodePrettily())
                      }
                    }
                  }

                } // ⬅️ succeed
              } // ⬅️ when
          } // ⬅️ publish


          println("🌍 HTTP server started on port ${httpPort}")
        } else {
          startPromise.fail(http.cause())
        }
      }
  }
}
