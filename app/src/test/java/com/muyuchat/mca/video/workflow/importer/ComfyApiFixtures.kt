package com.muyuchat.mca.video.workflow.importer

/** Inline, credential-free Comfy API fixtures for the importer tests. */
internal object ComfyApiFixtures {

    const val UNET_Q4_0 = "Wan2.2-TI2V-5B-Q4_0.gguf"
    const val UNET_Q4_K_M = "Wan2.2-TI2V-5B-Q4_K_M.gguf"
    const val CLIP_Q8_0 = "umt5-xxl-encoder-Q8_0.gguf"
    const val CLIP_Q4_K_M = "umt5-xxl-encoder-Q4_K_M.gguf"
    const val VAE = "Wan2.2_VAE.safetensors"

    fun t2v(
        unetName: String = UNET_Q4_0,
        clipName: String = CLIP_Q8_0,
        clipType: String = "wan",
        vaeName: String = VAE,
        width: Int = 480,
        height: Int = 832,
        length: Int = 33,
        batchSize: Int = 1,
        seed: Long = 7L,
        steps: Int = 20,
        cfg: String = "6.0",
        samplerName: String = "euler",
        scheduler: String = "normal",
        denoise: String = "1.0",
        shift: String = "3.0",
        fps: Int = 16,
        prompt: String = "a cat",
        negative: String = "blurry",
        saveFormat: String = "mp4",
        saveCodec: String = "h264",
    ): String = """
        {
          "1": {"class_type":"UNETLoader","inputs":{"unet_name":"$unetName","weight_dtype":"default"}},
          "2": {"class_type":"CLIPLoader","inputs":{"clip_name":"$clipName","type":"$clipType","device":"default"}},
          "3": {"class_type":"VAELoader","inputs":{"vae_name":"$vaeName"}},
          "4": {"class_type":"CLIPTextEncode","inputs":{"text":"$prompt","clip":["2",0]}},
          "5": {"class_type":"CLIPTextEncode","inputs":{"text":"$negative","clip":["2",0]}},
          "6": {"class_type":"EmptyHunyuanLatentVideo","inputs":{"width":$width,"height":$height,"length":$length,"batch_size":$batchSize}},
          "7": {"class_type":"ModelSamplingSD3","inputs":{"shift":$shift,"model":["1",0]}},
          "8": {"class_type":"KSampler","inputs":{"seed":$seed,"steps":$steps,"cfg":$cfg,"sampler_name":"$samplerName","scheduler":"$scheduler","denoise":$denoise,"model":["7",0],"positive":["4",0],"negative":["5",0],"latent_image":["6",0]}},
          "9": {"class_type":"VAEDecode","inputs":{"samples":["8",0],"vae":["3",0]}},
          "10": {"class_type":"CreateVideo","inputs":{"images":["9",0],"fps":$fps}},
          "11": {"class_type":"SaveVideo","inputs":{"video":["10",0],"filename_prefix":"video/test","format":"$saveFormat","codec":"$saveCodec"}}
        }
    """.trimIndent()

    fun i2v(
        imageName: String = "start.png",
        latentOutputIndex: Int = 2,
        width: Int = 480,
        height: Int = 832,
        length: Int = 33,
        seed: Long = 7L,
        prompt: String = "a cat",
        negative: String = "blurry",
    ): String = """
        {
          "1": {"class_type":"UNETLoader","inputs":{"unet_name":"$UNET_Q4_0","weight_dtype":"default"}},
          "2": {"class_type":"CLIPLoader","inputs":{"clip_name":"$CLIP_Q8_0","type":"wan"}},
          "3": {"class_type":"VAELoader","inputs":{"vae_name":"$VAE"}},
          "4": {"class_type":"CLIPTextEncode","inputs":{"text":"$prompt","clip":["2",0]}},
          "5": {"class_type":"CLIPTextEncode","inputs":{"text":"$negative","clip":["2",0]}},
          "6": {"class_type":"WanImageToVideo","inputs":{"positive":["4",0],"negative":["5",0],"vae":["3",0],"width":$width,"height":$height,"length":$length,"batch_size":1,"start_image":["12",0]}},
          "7": {"class_type":"ModelSamplingSD3","inputs":{"shift":3.0,"model":["1",0]}},
          "8": {"class_type":"KSampler","inputs":{"seed":$seed,"steps":20,"cfg":6.0,"sampler_name":"euler","scheduler":"normal","denoise":1.0,"model":["7",0],"positive":["4",0],"negative":["5",0],"latent_image":["6",$latentOutputIndex]}},
          "9": {"class_type":"VAEDecode","inputs":{"samples":["8",0],"vae":["3",0]}},
          "10": {"class_type":"CreateVideo","inputs":{"images":["9",0],"fps":16}},
          "11": {"class_type":"SaveVideo","inputs":{"video":["10",0],"filename_prefix":"video/test","format":"mp4","codec":"h264"}},
          "12": {"class_type":"LoadImage","inputs":{"image":"$imageName","upload":"image"}}
        }
    """.trimIndent()

    fun withExtraNode(json: String, id: String = "12", body: String): String =
        json.replace("\"11\": {", "\"$id\": $body,\n  \"11\": {")

    fun nodes(count: Int): String = buildString {
        append('{')
        repeat(count) { index ->
            if (index > 0) append(',')
            append("\"${index + 1}\":{\"class_type\":\"LoadImage\",\"inputs\":{\"image\":\"img.png\"}}")
        }
        append('}')
    }
}
