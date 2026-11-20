<div align="center">
    <h1 align="center">Artemis-Notifications</h1>
</div>

Notification Relay for Artemis Push Notifications.  
Allows secure and private push notifications from [Artemis](https://github.com/ls1intum/Artemis) to the mobile apps for [iOS](https://github.com/ls1intum/artemis-ios) and [Android](https://github.com/ls1intum/artemis-android).

![](hermes-system-decomposition.png)

### How to run:
1. Replace placeholder values `<...>` in Docker-Compose file
2. Adjust port if necessary
3. Run `docker-compose up`

### Further Information on Dockerfile

To run the services as an APNS relay the following Environment Variables are required (token-based authentication, as described [here](https://developer.apple.com/documentation/usernotifications/establishing-a-token-based-connection-to-apns)):
- APNS_TOKEN_KEY_PATH: String - Path to the APNs signing key (.p8 file) inside the container
- APNS_TEAM_ID: String - The Apple Developer Team ID that owns the key
- APNS_KEY_ID: String - The Key ID of the APNs signing key
- APNS_PROD_ENVIRONMENT: Bool - True if it should use the Production APNS Server (Default false)

Optionally, one relay can serve both TestFlight/App Store builds (production device tokens) and Xcode debug builds (sandbox device tokens):
- APNS_FALLBACK_ENVIRONMENT_ENABLED: Bool - If true, a notification that the APNS server selected by APNS_PROD_ENVIRONMENT rejects as `BadDeviceToken` is retried once on the other APNS server (Default false)

Leave it off for a relay that must only talk to the Production APNS Server. The fallback needs a signing key that Apple allows for both environments (Sandbox & Production). It never affects the health endpoint; if the key is not valid for the other environment, those tokens are simply rejected.

Furthermore the <APNS_Key>.p8 needs to be mounted into the Docker under the above specified path.
The provided docker-compose.yml does this for you: it sets APNS_TOKEN_KEY_PATH itself and mounts the key from the host path given in APNS_KEY_PATH (see example.env).


To run the services as a Firebase relay the following Environment Variable is required:
- GOOGLE_APPLICATION_CREDENTIALS: String - Path to the firebase.json
Furthermore the Firebase.json needs to be mounted into the Docker under the above specified path.

To run both APNS and Firebase configure the Environment Variables for both.

### Running as a non-root user

The container runs as the unprivileged user `hermes` (uid/gid 10001). The mounted APNs signing key and Firebase credentials therefore have to be **readable by uid 10001**, for example:

```bash
sudo chown 10001:10001 /opt/hermes/AuthKey_<apns_key_id>.p8 /opt/hermes/artemis.json
sudo chmod 400 /opt/hermes/AuthKey_<apns_key_id>.p8 /opt/hermes/artemis.json
```

Keys that are only readable by root (e.g. `-rw------- root root`) make the relay start unhealthy ("Could not init APNS service" / "Exception while loading Firebase credentials" in the log). The image has a Docker `HEALTHCHECK` on `/api/push_notification/alive`.

### Image tags and rollback

Every build of `main` is published as `latest`, as `main` and as an immutable `sha-<short commit>` tag (e.g. `ghcr.io/ls1intum/hermes:sha-d6226b1`). Deploy a `sha-...` tag to pin an exact build; to roll back, deploy the `sha-...` tag of the previous good build again.

### Optional APNs settings

All of these can also be set via environment variables (e.g. `apns.alert-priority` -> `APNS_ALERT_PRIORITY`); defaults are in `hermes/src/main/resources/application.properties`:
- `apns.topic` - the app's bundle identifier (default `de.tum.cit.ase.artemis`)
- `apns.expiration-days` - how long APNs retries delivery to an offline device (default 7)
- `apns.alert-priority` - delivery priority of visible notifications, `10` (immediately) or `5` (may be delayed to save power; default 5). Background notifications always use 5.

### Metrics

Queue depth and active workers per provider (`executor_queued_tasks`, `executor_active_threads`) as well as the number of requests answered with 503 because the queue was full (`hermes_send_rejected_total`) or the response timeout fired (`hermes_send_timeouts_total`) are exposed in Prometheus format at `/actuator/prometheus` on the **management port 8081**. That port is not published by `docker-compose.yml`, so it is not reachable through the public relay port; scrape it from the Docker network (e.g. a Prometheus container in the same compose network) or publish it to localhost only (`127.0.0.1:8081:8081`).

### Request validation

`send_apns` and `send_firebase` answer `400 Bad Request` when `initializationVector`, `payloadCipherText` or `token` is missing or blank (`apiType` is optional because Android requests do not carry it).