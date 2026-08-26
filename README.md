# CreativeManager

CreativeManager is a Spigot plugin allow admin to manage what creative player can do.

## Configuration upgrades

The Patriam-maintained build versions `config.yml` and each installed bundled language catalog
independently with a top-level `config-version`. Version `1` is the first managed schema. A real
existing file with no physical marker is schema `0`, so it is upgraded automatically; it does not
need to be copied, recreated, or have new settings pasted at the bottom.

During a schema upgrade, CreativeManager builds the new file from the current bundled template and
then overlays explicit operator values. This gives new keys their documented default position and
current comments while recursively preserving unknown extension keys after the known keys in their
section. The old `blacklist` section is migrated to `list`, with an explicit new-path value winning
if a hybrid file contains both. Locally added comments are intentionally replaced by the bundled
documentation.

Before replacement, the original bytes are saved beside the file as (for example)
`config.yml.v0.bak`. Replacement is compare-and-swap and atomic, and the promoted bytes are read and
validated again. A missing managed file is also installed atomically from its bundled bytes, without
a migration backup. Blank, null, duplicated, quoted, tagged, anchored, malformed, negative, or
future markers block startup/reload without changing the source file. Explicit null values and YAML
merge keys, non-string mapping keys, and additional YAML documents are likewise rejected rather
than silently discarded. These files use an open extension-key policy and contain no credentials,
so owner-only credential handling is not applicable.

The five language files historically installed by CreativeManager are still installed and managed.
The other bundled catalogs are managed when selected or already present. An installed custom
language is upgraded against the English catalog as its template, preserving its translations and
extensions; selecting a custom language file that does not exist blocks activation. A failed reload
keeps the previous settings, messages, and periodic-save schedule active.

Startup logs and `/cm info` report the supported/installed schema and whether a failed attempt left
the previous known-good runtime generation active, without printing configured values.

[![Build Status](https://travis-ci.com/K0bus/CreativeManager.svg?branch=master)](https://travis-ci.com/K0bus/CreativeManager) [![GitHub issues](https://img.shields.io/github/issues/K0bus/CreativeManager.svg)](https://github.com/K0bus/CreativeManager/issues/) [![GitHub issues-closed](https://img.shields.io/github/issues-closed/K0bus/CreativeManager.svg)](https://github.com/K0bus/CreativeManager/issues?q=is%3Aissue+is%3Aclosed) [![Open Source? Yes!](https://badgen.net/badge/Open%20Source%20%3F/Yes%21/blue?icon=github)](https://github.com/Naereen/badges/) [![Discord](https://img.shields.io/discord/578609953066057758?color=blue&label=Discord)](https://discord.gg/EabMR9S)

## Download on Spigot

![Banner](https://api.mcbanners.com/banner/saved/lWZEKOYEhqeoNS.png)

## Wiki

[CreativeManager Wiki moved here](https://wiki.k0bus.fr)


## Maven API

You can use CreativeManager in your own plugins with our Maven repo

[![](https://jitpack.io/v/K0bus/CreativeManager.svg)](https://jitpack.io/#K0bus/CreativeManager)

```xml
<repositories>
    <repository>
        <id>jitpack.io</id>
        <url>https://jitpack.io</url>
    </repository>
</repositories>
```

```xml
<dependency>
    <groupId>com.github.K0bus</groupId>
    <artifactId>CreativeManager</artifactId>
    <version>1.21</version>
</dependency>
```


## Javadoc

https://javadocs.k0bus.fr/creativemanager/index.html
