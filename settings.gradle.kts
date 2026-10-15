/*
 * This file is part of LiquidBounce (https://github.com/CCBlueX/LiquidBounce)
 *
 * Copyright (c) 2015 - 2023 CCBlueX
 *
 * LiquidBounce is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * LiquidBounce is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with LiquidBounce. If not, see <https://www.gnu.org/licenses/>.
 */

pluginManagement {
    repositories {
        maven {
            name = "Fabric"
            url = uri("https://maven.fabricmc.net/")
        }
        // NOTE (environment patch): plugins.gradle.org and repo1.maven.org are
        // unreachable from this machine; aliyun mirrors both.
        maven {
            name = "AliyunPluginPortal"
            url = uri("https://maven.aliyun.com/repository/gradle-plugin")
        }
        maven {
            name = "AliyunCentral"
            url = uri("https://maven.aliyun.com/repository/public")
        }
    }

    plugins {
        val loom_version: String by settings
        val kotlin_version: String by settings
        id("fabric-loom") version loom_version
        kotlin("jvm") version kotlin_version
    }
}

rootProject.name = "LiquidBounce"
