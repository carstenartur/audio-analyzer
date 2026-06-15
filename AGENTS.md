# Agent validation requirements

Before committing any change, run:

```bash
mvn -B clean verify --file pom.xml
```

Do not commit changes that introduce PMD, Checkstyle, SpotBugs, formatting, or test failures.
