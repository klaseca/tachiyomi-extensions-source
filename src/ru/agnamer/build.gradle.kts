import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "Agnamer"
    versionCode = 1
    contentWarning = ContentWarning.MIXED
    libVersion = "1.6"

    source {
        lang = "ru"
        id = 8983242087533137528L
        baseUrl = "https://agnamer.local"
    }

    deeplink {
        path("/manga/..*")
    }
}
