# Testing the Bank Price Changes Plugin

## Prerequisites

- Java 11 (matches `sourceCompatibility` in `build.gradle`)
- A RuneLite account (required to launch the client)
- Gradle (or use the included `./gradlew` wrapper)

The RuneLite client JAR must be available in your local Maven repository. RuneLite's
build process publishes it there automatically if you've built the RuneLite client
locally, or you can pull it from `https://repo.runelite.net`.

## Running in the RuneLite Client

The test class `BankPriceChangesPluginTest` is not a unit test — it boots a full
RuneLite client with the plugin loaded. Run it with:

```bash
./gradlew run
```

This executes the `run` task defined in `build.gradle`:

```groovy
task run(type: JavaExec) {
    classpath = sourceSets.test.runtimeClasspath
    mainClass = 'com.bankpricechanges.BankPriceChangesPluginTest'
    jvmArgs = ['-ea']
}
```

The `-ea` flag enables Java assertions.

Once the client opens:

1. Log in to your RuneLite account.
2. Open your bank in-game.
3. The overlay should appear showing price change data for items in your bank.

## What the Test Class Does

`BankPriceChangesPluginTest.main()` calls:

```java
ExternalPluginManager.loadBuiltin(BankPriceChangesPlugin.class);
RuneLite.main(args);
```

This registers the plugin as a built-in external plugin and starts the full client,
so you can interact with it exactly as a player would.

## Troubleshooting

- **Build fails with missing RuneLite dependency** — ensure the RuneLite version in
  `build.gradle` (`runeLiteVersion = '1.12.16'`) matches what is available in your
  local Maven repo or on `https://repo.runelite.net`.
- **Client won't start** — confirm you are using Java 11. Newer JDKs may have
  compatibility issues with older RuneLite versions.
- **Plugin not appearing** — check the RuneLite plugin panel and ensure
  "Bank Price Changes" is enabled.
