package com.muyuchat.mca

fun MainViewModel.maybeCheckForAppUpdate() = appUpdateOwner.maybeCheckForAppUpdate()
fun MainViewModel.checkForAppUpdate() = appUpdateOwner.checkForAppUpdate()
fun MainViewModel.setAppUpdateAutoCheckEnabled(enabled: Boolean) = appUpdateOwner.setAppUpdateAutoCheckEnabled(enabled)
fun MainViewModel.downloadAppUpdate() = appUpdateOwner.downloadAppUpdate()
fun MainViewModel.installAppUpdate() = appUpdateOwner.installAppUpdate()
fun MainViewModel.openAppUpdateRelease() = appUpdateOwner.openAppUpdateRelease()
