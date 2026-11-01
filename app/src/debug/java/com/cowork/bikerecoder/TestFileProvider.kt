package com.cowork.bikerecoder

import androidx.core.content.FileProvider

/**
 * The debug build's FileProvider for device tests (`${applicationId}.testfiles`). Its own class because the main manifest
 * declares `androidx.core.content.FileProvider` itself for the share cards (`${applicationId}.share`) and the manifest
 * merger keys providers by class name.
 */
class TestFileProvider : FileProvider()
