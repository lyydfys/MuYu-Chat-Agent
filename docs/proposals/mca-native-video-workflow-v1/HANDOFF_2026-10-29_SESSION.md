# MCA 鍘熺敓瑙嗛宸ヤ綔娴?v1 鈥斺€?浼氳瘽浜ゆ帴鏂囨。锛?026-10-29锛?
> 浜ゆ帴瀵硅薄锛氭帴鎵?`mca-native-video-workflow-v1` 鐨勫伐绋嬪笀/浠ｇ悊銆?> 鏈枃鍙啓**鏈細璇濆疄娴嬪緱鍒扮殑浜嬪疄**锛涙湭楠岃瘉鐨勪竴寰嬫爣娉?NOT_RUN / 鎺ㄦ祴澶勫凡娉ㄦ槑銆?
---

## 0. 涓€鍙ヨ瘽缁撹

**CPU 璺緞宸插埌鏋侀檺**锛氫袱涓嚧鍛芥牴鍥狅紙UMT5 璇嶈〃鍦?Android 琚紪璇戞帓闄ゃ€丟GUF reader 閬?5D 寮犻噺涓锛夐兘宸插畾浣嶅苟淇銆佷笖璺ㄨ澶囩湡鏈洪獙璇侊紱浣?*璇箟浠嶄笉鍙鲸璇嗐€侀绠椾粛宸?30鈥?0脳**銆傚敮涓€鑳藉悓鏃惰В鍐充袱鑰呯殑鏄?**NPU(QNN/HTP) + 4-step 钂搁 + 鍒嗗潡/娴佸紡瑙ｇ爜**鈥斺€旂幆澧冨凡楠岃瘉鍙敤锛?*鍗″湪 20GB 鍘熷鏉冮噸涓嬭浇涓嶄笅鏉?*銆?
---

## 1. 鍏淮鐘舵€侊紙鍙洿鎺ュ鍒惰繘姹囨姤锛?
| 缁村害 | 缁撹 | 鍏抽敭璇佹嵁 / 澶囨敞 |
|---|---|---|
| 婧愮爜瀹炵幇 | **PASS** | 7 椤逛慨澶?+ 5D 鍫嗗彔 + VAE tiling 寮€鍏?+ Turbo LoRA 閫氶亾锛屽潎鏈夌湡鏈烘棩蹇楀洖鏄?|
| JVM | **PASS** | 褰撳墠鍏变韩鏍戯細18 濂椾欢 / 162 鐢ㄤ緥 / 0 澶辫触 / 0 璺宠繃锛堝彟 contract 45銆亀orkspace 36銆乬ate 73銆乧hat 491銆佽縼绉?71锛?|
| 杩佺Щ | **PASS** | JVM 29鈫?5 閾?+ 璁惧 `MigrationTestHelper` **OK (3 tests)** |
| 璁惧鐢熶骇閾?| **PASS** | device-a锛圫M8550/Android16锛? device-b锛圫M8750P/Android17锛夛細T2V/I2V銆乬ate 鎸侀攣 BUSY鈫扐CQUIRED銆侀攣绛夊緟涓彇娑?131ms銆侀噸璇曘€佽繘绋嬫浜?INTERRUPTED銆佹挱鏀?鍒嗕韩/鍙﹀瓨/鍒犻櫎 |
| 璧勪骇涓嬭浇锛堜笁浠跺锛?| **PASS** | 10,481,555,776 B锛涘弻璁惧锛涗笁 sha 鍏ㄧ瓑 pin锛涘惈鏆傚仠/缁紶/鏂綉鎭㈠瀹炴祴 |
| 鐢昏川 | **FAIL / NOT_MET** | 64脳64脳9脳4銆?28脳128脳9脳4銆?28脳128脳9脳20銆?56脳256脳9脳4銆佸惈/涓嶅惈 Turbo LoRA 鈥斺€?**鍏ㄩ儴鏃犲彲杈ㄨ瘑涓讳綋**锛?80脳832 鏈彇寰楁湁鏁堟暟鎹?|
| 鎬ц兘棰勭畻 | **FAIL / NOT_MET** | 128虏脳9脳20 = 19.3 min锛?28虏脳9脳4+LoRA = 10.4 min锛?28虏脳9脳4 鏃?LoRA 鈮?10.4 min锛?80p 鍦?CPU 涓婅 LMKD/鐪嬮棬鐙楁尅浣?|
| 杩滅 ComfyUI | **BLOCKED** | 鏃?endpoint锛?188/8189/8000 鍧囨棤鐩戝惉锛孶I 濡傚疄 `REMOTE_TIMEOUT`锛?|
| NPU 閫氶亾 | **鐜 READY锛岃祫浜ф湭灏辩华** | WSL Ubuntu-22.04 + Python 3.10.12 涓?`qairt-converter --help` 瀹炴祴鍙繍琛岋紱缂?20GB 鍘熷 safetensors |

**V1 鏈畬鎴愶紝灞炲疄楠岃兘鍔?*锛涗笉寰楁妸 64脳64/128脳128 鐨勭粨鏋滃啓鎴愪骇鍝侀€氳繃銆?
---

## 2. 鏈細璇濊惤鍦扮殑浠ｇ爜鏀瑰姩锛堝叏閮ㄥ凡缂栬瘧閫氳繃锛?
| 鏂囦欢 | 鏀瑰姩 | 璇存槑 |
|---|---|---|
| `app/src/test/.../video/model/VideoModelAssetOwnerTest.kt` | 鐢?绛夊埌缁堟€?phase"鏇夸唬 `advanceUntilIdle` 鍚庣珛鍗宠 StateFlow | 淇帀 7/8 鍋囧け璐ワ紱鍚?`.part` 棰勬涓庡浐瀹?revision 鏂█ |
| `app/src/main/.../video/model/VideoModelAssetOwner.kt` | 棰勬鏀逛负 `size - existingPartialBytes` + 256MiB 浣欓噺锛沗remoteFor()` 璧伴暅鍍忛閫夊湴鍧€ | 缁紶涓嶅啀璇垽绌洪棿涓嶈冻 |
| `app/src/main/.../video/model/Wan22Ti2v5bProfiles.kt` | `sourceUrl` 鍏ㄩ儴鏀逛负 `resolve/<commit>/<path>`锛圦UANTSTACK `57437632鈥銆丆ITY96 `b535255b鈥锛?| 鍘绘帀鍙彉 `resolve/main` |
| `core/download/.../HuggingFaceTransferUrl.kt`锛堟柊锛? 娴嬭瘯 | 闀滃儚/鐩磋繛 URL 閫夋嫨 | 3/3 PASS |
| `app/src/main/.../video/execution/VideoGenerationOwner.kt` | 鏂板 `draftSeedOf(document)`锛歰verride 鈫?draft seed鈮? 鍥哄畾 鈫?鍚﹀垯闅忔満 | 淇?鍚?seed 涓嶅悓 prompt"瀹為獙鏃犳硶鎴愮珛鐨勯棶棰橈紱OwnerTest 15/15 |
| `app/src/main/.../video/worker/LocalVideoWorkerAdapter.kt` | 鎵ц鍓?`promptMatchesSnapshot()` 鏍￠獙鏂囨。 prompt 涓庡揩鐓?`promptSha256` | 闃叉"鏀硅瘝娌跨敤鏃ф枃妗?闈欓粯璺戦敊锛涙柊澧?`PromptIntegrityTest` 4/4 |
| `core/sd-native/src/main/cpp/sd_video_generate.cpp` | 鈶?sd.cpp 鏃ュ織鎺ュ洖 logcat锛坄sd.cpp:` 鍓嶇紑锛夆憽 璇佹嵁琛岋紙妯″瀷/鍑犱綍/seed/guidance/**prompt 鎸囩汗**锛夆憿 `flashAttn` 寮€鍏?鈶?`vaeTiling/tileSizeX/tileSizeY/tileOverlap` 鈶?`loraPath/loraWeight` | 鍏ㄩ儴榛樿鍏抽棴/涓嶆敼鍙樻棦鏈夎涓猴紱褰撳墠 sha 鍓嶇紑 `1585E122鈥 |
| `third_party/stable-diffusion.cpp/src/tokenizers/vocab/vocab.cpp` | `load_umt5_tokenizer_json()` 鍘绘帀 `#if defined(__ANDROID__) return {}`锛涜ˉ `#include "umt5.hpp"` | **鏍瑰洜淇**锛堣瑙?搂4.1锛?|
| `third_party/stable-diffusion.cpp/src/tokenizers/t5_unigram_tokenizer.cpp` | `BuildTrie` 鏀规寜 unsigned 瀛楄妭搴忔帓搴忥紱鍔?`MCA t5 tokenizer init: 鈥?status=鈥 璇婃柇 | 閿簭淇 + 鍙娴嬫€?|
| `third_party/stable-diffusion.cpp/ggml/src/gguf.cpp` | 閬?`n_dims > 4` 涓嶅啀 `break`锛屾敼涓烘妸 鈮? 缁?*鍫嗗彔杩?ne[3]**锛堝榻?sd.cpp 鑷韩 reader `gguf_reader_ext.h:156-161`锛?| **鏍瑰洜淇**锛堣瑙?搂4.2锛夛紱鍘熸枃浠跺浠?`E:\MAC\.dsh-tmp\gguf.cpp.v111.bak`锛屽爢鍙犲疄鐜板彟瀛?`.dsh-tmp/gguf.cpp.v111-stack.cpp` |
| `app/src/androidTest/.../VideoNativeBudgetDeviceTest.kt` | payload 鏀寔 `flashAttn / vaeTiling / tileSizeX / tileSizeY / tileOverlap / loraPath / loraWeight`锛堜粎鍦ㄤ紶鍏ユ椂鍐欙級 | 鎺㈤拡鍙帶 |

**鏈惤鍦?*锛堝彧鏈夊浠?璁板綍锛夛細鏃犮€?
---

## 3. 璧勪骇 pin锛堟寮忥級

### 3.1 涓変欢濂楋紙base 妯″瀷锛屽凡楠岃瘉鍦ㄤ袱鍙拌澶囦笂锛?
| 瑙掕壊 | 鏂囦欢 | 澶у皬 | SHA-256 |
|---|---|---|---|
| DiT | `Wan2.2-TI2V-5B-Q4_0.gguf`锛圦uantStack @ `57437632鈥锛?| 3,029,086,560 | `fcf40dd6鈥 |
| 鏂囨湰缂栫爜鍣?| `umt5-xxl-encoder-Q8_0.gguf`锛坈ity96 @ `b535255b鈥锛?| 6,043,068,256 | `2521d4de鈥 |
| VAE | `wan2.2_vae.safetensors` | 1,409,400,960 | `e40321bd鈥 |

鍚堣 **10,481,555,776 B**锛沠9d 涓?device-b 鍚勪竴浠斤紝鍧?sha 鍏ㄧ瓑銆?
### 3.2 Turbo 4-step 钂搁 LoRA锛堟湰浼氳瘽鏂板锛屽凡 pin锛?
| 椤?| 鍊?|
|---|---|
| 浠撳簱 | `hum-ma/Wan2.2-TI2V-5B-Turbo-GGUF` |
| Commit | `9873ba175b4b204a81f4a43c9644e6c385ee4459` |
| 鏂囦欢 | `Wan22_TI2V_5B_Turbo_lora_rank_64_fp16.safetensors` |
| 澶у皬 | **332,348,584 B** |
| LFS SHA-256 | **`0ace5244e3d1256f884662c261b017249796cf5b95f05d5ed93cc02a478967b8`** |
| 鏍￠獙 | 涓绘満涓嬭浇鍚庝竴鑷达紱`sha256sum` 鍦?device-a/device-b 鍧囦竴鑷?|
| 鏀剧疆 | 涓绘満 `E:\MAC\.tmp-models\`锛涜澶?`/data/local/tmp/wan_lora/`锛堝凡 `chmod 644`锛?|
| 璁稿彲 | apache-2.0锛坆ase_model `quanhaol/Wan2.2-TI2V-5B-Turbo`锛?|

澶囬€夛紙鍚屼粨搴擄紝鏈笅杞斤級锛歚Wan2_2_5B_FastWanFullAttn_lora_rank_128_bf16.safetensors`锛?60,874,456B锛宻ha256 `7929049鈥锛夈€佹暣妯″瀷 `Wan2_2-TI2V-5B-Turbo-Q4_0.gguf`锛?,033,897,696B锛宻ha256 `8aa3a98c鈥锛夈€?
### 3.3 寰呬笅杞斤細NPU 杞崲鐢ㄥ師濮嬫潈閲嶏紙`Wan-AI/Wan2.2-TI2V-5B` @ `921dbaf3鈥锛?
| 鏂囦欢 | 澶у皬 | LFS SHA-256 |
|---|---|---|
| `diffusion_pytorch_model-00001-of-00003.safetensors` | 9,825,014,472 | `720b06c4ade5e87c1246bba8ac95b664c638749cd9b102cf84d823bb44c026a1` |
| `diffusion_pytorch_model-00002-of-00003.safetensors` | 9,995,661,736 | `09ec5ef720d8396f6cfa51fbdcbdb2327e37722afd6e89fd38f1e7e5e782c283` |
| `diffusion_pytorch_model-00003-of-00003.safetensors` | 178,558,176 | `6306f7894c345de9093ad588771c2abfaeb668a81f7a6d9a918bd26ba3568e49` |

鐩爣鐩綍锛歚E:\MAC\.tmp-models\wan22-ti2v-5b-pth\`銆?*UMT5 涓?VAE 鏃犻渶鍐嶄笅**锛堝凡鏈夛級銆?
---

## 4. 涓や釜鏍瑰洜锛堟湰浼氳瘽鏈€鏈変环鍊肩殑浜у嚭锛?
### 4.1 Android 涓?UMT5 璇嶈〃琚紪璇戞帓闄?鈫?prompt 鎭?0 token

`third_party/stable-diffusion.cpp/src/tokenizers/vocab/vocab.cpp` 鍘熸湰锛?
```cpp
std::string load_umt5_tokenizer_json() {
#if defined(__ANDROID__)
    return {};                    // 鈫?绌轰覆
#else
    ...
#endif
}
```

涓?`umt5.hpp` 鐨?include 涔熷湪 `#if !defined(__ANDROID__)` 閲屻€備簬鏄?`InitializePieces("")` 鐨?`json::parse("")` 鎶涢敊 鈫?`status_=INVLIAD_JSON(5)` 鈫?璇嶈〃绌?鈫?`EncodeOptimized` 杩斿洖 `{}` 鈫?**姣忎釜 prompt 閮借鍒囨垚 0 token**锛屾潯浠朵笌 prompt 鏃犲叧銆?
**淇鍚庣湡鏈鸿瘉鎹紙device-a锛?*锛?```
MCA t5 tokenizer init: is_umt5=1 pieces=256300 status=0 trie_results=13 pairs=256300
split prompt "red ball on blue table" to tokens ["鈻乺ed","鈻乥all","鈻乷n","鈻乥lue","鈻乼able",]
```
鏉′欢閾?A/B锛堝悓 seed 42锛?4脳64脳9脳4锛夛細甯?0/4/8 **sha 鍏ㄤ笉鍚?*锛宮eanAbsDiff **24.291 / 28.642 / 40.373**锛孭SNR **19.962 dB**锛?8a 涓婂鐜?*閫愪綅涓€鑷?*锛圕PU 璺緞纭畾鎬э級銆?
### 4.2 GGUF reader 閬?5D 寮犻噺涓 鈫?涓?11 涓?DiT 寮犻噺

`ggml/src/gguf.cpp:611` 鍘熸湰 `if (n_dims > GGML_MAX_DIMS) { 鈥? ok=false; break; }`銆侱iT GGUF 鐨?`patch_embedding.weight` 鏄?**5D `[2,2,1,48,3072]`锛堢储寮?814/825锛?*锛屼簬鏄叾鍚庣殑 **T815鈥揟824 鍏?10 鏉?tensor info 浠庢湭琚鍙?*锛岀己澶?**11 涓紶閲?*锛歚patch_embedding.weight`銆乣text_embedding.0/2`锛?锛夈€乣time_embedding.0/2`锛?锛夈€乣time_projection.1`锛?锛夆€斺€斾笖**瀹屽叏闈欓粯**锛堟棤 per-tensor 鍛婅锛夈€?
**閿欒灏濊瘯锛堝凡鍥炴粴锛屽嬁閲嶇姱锛?*锛氱洿鎺?涓㈠純 singleton 缁?寰楀埌 `[2,2,48,3072]`锛屼細璁?`patch_embedding_channels` 浠?**147456 鈫?3072**锛孌iT 琚鍒ゆ垚 **Wan2.1-T2V-1.3B**锛岄殢鍚?shape mismatch 鈫?`MODEL_LOAD_FAILED`銆?
**姝ｇ‘淇硶**锛?*鍫嗗彔**锛坄shape[3] *= shape[i]` for i鈮?锛夛紝涓?sd.cpp 鑷繁鐨?metadata reader 涓€鑷达紝涔熷尮閰?`CausalConv3d` 鏈熸湜鐨?`[kw,kh,kd,in*out] = [2,2,1,147456]`銆?
**鐪熸満璇佹嵁锛?8a锛?*锛?```
tensor 'patch_embedding.weight' stacked 5D shape (2, 2, 1, 48, 3072) -> (2, 2, 1, 147456)
Version: Wan 2.2 TI2V
memory-mapped 620 tensors in 3 files (9982.85 MB)
context model=Wan 2.2 TI2V supports_video=true distilled_guidance=false
```
娉細`memory-mapped 620`锛堥噺鍖栨潈閲?mmap锛変笌 `total params 10.62MB`锛堥潪 mmap 鍙傛暟缂撳啿锛夋槸**涓ょ鍙ｅ緞**锛屼笉鐭涚浘銆?
---

## 5. 瀹炴祴鏁版嵁锛堝彲鐩存帴寮曠敤锛?
| 閰嶇疆 | 鏉′欢 | 閲囨牱 | 瑙ｇ爜 | 鎬昏 | 澶囨敞 |
|---|---|---|---|---|---|
| 64脳64脳9脳1锛堝熀绾匡級 | 147.29 s | 33.57 s | 44.18 s | 225.04 s | device-a |
| 64脳64脳9脳1锛坱iling 32脳32@0.5锛?| 191.37 s | 38.66 s | **50.20 s** | 280.22 s | tiling **鎱?13.6%**锛屽抚 PSNR=鈭烇紙鏃犳帴缂濓級 |
| 128脳128脳9脳4 鏃?LoRA | 鈥?| ~170 s | ~185 s | 鈮?25 s | 鈥?|
| **128脳128脳9脳4 + Turbo LoRA@1.0** | 145.67 s | **236.39 s** | 198.71 s | **625.92 s** | device-a锛沗lora=鈥1.000000` 宸茬‘璁ょ敓鏁?|
| **128脳128脳9脳20**锛堝悎瑙勬鏁帮級 | 132.16 s | **842.89 s** | 184.76 s | **1159.81 s** | 甯?`probe-frame-0.png` 浠嶄负鎶借薄鑹插甫 |
| 256脳256脳9脳4 | 123.20 s | 301.58 s | **602.34 s** | **1056 s** | device-b锛涘嘲鍊?PSS 6,748,616 KB锛涙贩娌屽僵鑹叉ā绯?|
| 480脳832脳9脳1 鍩虹嚎 | 89.76 s | 619.93 s | 鏈畬鎴?| INTERRUPTED | 22 鍒嗛挓涓婇檺涓 |
| 480脳832脳9脳1 tiling 512脳288 | 93.28 s | 290.89 s | 杩涜涓?| **LMKD killed** | `lowmemorykiller: thrashing`銆丷SS鈮?GB |
| 480脳832脳9脳4 + LoRA锛?8a锛?| 鈥?| 鈥?| 鈥?| **NOT_RUN** | 棰勬闃舵鎸?35 鍒嗛挓鍚?`Process crashed`锛屾棤 native 鏃ュ織 |

**璇箟鍒ゅ畾锛堝叏閮ㄤ负"鏃犲彲杈ㄨ瘑涓讳綋"锛?*锛?4虏脳4銆?4虏脳20銆?28虏脳4锛堟棤/鏈?LoRA锛夈€?28虏脳20銆?56虏脳4銆?**甯ц瘉鎹?*锛歚build/evidence/v1-10/`锛圓/B 甯э級銆乣v1-16/device-a-128x128-20steps-probe-frame-0.png`銆乣v1-17/device-a-128x128-4steps-LORA-frame0.png`銆?
**棰勭畻澶栨帹锛堣瘹瀹烇級**锛?80脳832脳33脳20 鐨勯噰鏍烽噺绾︿负 128虏脳9脳20 鐨?**36脳**锛堝儚绱?9.9脳 脳 甯?3.67脳锛夆噿 CPU 閲囨牱绾?**8.5 灏忔椂**锛岃В鐮佹洿澶氥€備笌鍙傝€冮」鐩?NPU+4-step 鐨?**832脳480/5s < 3 鍒嗛挓**鐩稿樊绾︿袱涓暟閲忕骇銆?
---

## 6. 闃诲涓庡緟鍔烇紙鎸変紭鍏堢骇锛?
### P0 鈥斺€?20GB 鍘熷鏉冮噸涓嬭浇锛堥樆濉?NPU 鍏ㄩ摼璺級
hf-mirror 鐨勮繛鎺ユ瘡 1鈥? GB 閲嶇疆銆備笁娆″皾璇曞潎澶辫触锛?
| 鏂瑰紡 | 缁撴灉 |
|---|---|
| 鏅€?curl | 鍒?566 MB 鏂紝sha 涓嶇 |
| `curl -C -` 寰幆 | 鍒?8.4 GB锛屼絾澶氳繘绋嬪苟鍙戝啓瀵艰嚧鎴柇/瓒呴暱 |
| 256MiB 鍒嗗潡 Range锛堝崟涓€ `pty-1`锛?| 鍒?512 MB锛?.5%锛夊悗娈靛唴鍙嶅閲嶈瘯锛寏1.4 MB/s |

**宸查獙璇?*锛氶暅鍍?*鏀寔 Range**锛坄bytes=0-1023` 绮剧‘杩斿洖 1024 瀛楄妭锛夈€?**鍏抽敭鏁欒**锛氫笉瑕佺敤 `run_in_background`/`Start-Job` 璧峰涓笅杞藉惊鐜€斺€旇法宸ュ叿璋冪敤鐪嬩笉瑙佺殑杩涚▼浼氬苟鍙戝啓鍚屼竴鏂囦欢銆?*蹇呴』鍗曚竴鎸佷箙杩涚▼**锛坄terminal_open` 鐨勪細璇濇垨鍓嶅彴鍛戒护锛夈€?
**鎭㈠鏂瑰紡锛堜换涓€锛?*锛氣憼 鎵嬪伐鎶婁笁鏂囦欢鏀捐繘鐩爣鐩綍锛涒憽 鎻愪緵鏇村揩闀滃儚/浠ｇ悊/`HF_TOKEN`锛涒憿 鎺ュ彈鎱㈤€熼暱璺戙€?
### P1 鈥斺€?NPU 杞崲閾撅紙鐜宸查獙璇侊紝浠ｇ爜鏈啓锛?```
WSL: PYTHONPATH=/mnt/e/tools/qairt-sdk/2.45.0.260326/lib/python \
     python3 /mnt/e/tools/qairt-sdk/2.45.0.260326/bin/x86_64-linux-clang/qairt-converter --help   # 宸插疄娴嬪彲鐢?```
鍚庣画锛歴afetensors 鈫?ONNX锛堥渶 torch/diffusers锛?*涓绘満涓?WSL 鍧囨湭瀹夎**锛夆啋 `qairt-converter` 鈫?`qnn-context-binary-generator`锛圚TP V73=device-a / V79=device-b锛夆啋 `qairt-accuracy-debugger` 鏍￠獙 鈫?鎺ョ嚎 `:local_video`锛堣皟鐢ㄧ偣涓嶅彉锛屽彧鎹㈡墽琛屽悗绔級銆?
### P2 鈥斺€?涓?NPU 鏃犲叧銆佸彲骞惰鐨勬敹灏鹃」
- **TalkBack 鍙惉鏈楄**锛堜换鍔?6锛岀幇 NOT_RUN锛夛細adb 闅句互绋冲畾椹卞姩 MIUI TalkBack 鐒︾偣锛岄渶瑕佺湡瀹炰汉宸ユ垨鏀圭敤 `AccessibilityService` 鏂█ + 褰曢煶鍙栬瘉銆?- **image脳video 鐪熷疄绔炰簤**锛堜换鍔?4 鍓╀綑锛夛細涓ゆ満鍧囨棤鍙敤鐢熷浘浠诲姟锛圔LOCKED锛夛紱鍙厛鐢ㄦ祴璇曟々璇佹槑 gate 涓茶/鎺掗槦璇箟銆?- **瑙ｇ爜鍐呭瓨绛栫暐**锛氳 480p 涓嶈 LMKD 鏉€锛堟寜鏃堕棿鐗囨祦寮忚В鐮併€佷笉鎸佸叏鍒嗚鲸鐜囦腑闂撮噺锛夈€傛敞鎰忥細鍗充娇瑙ｅ喅锛孋PU 480p 浠嶄笉鍙敤浜庝骇鍝併€?
### P3 鈥斺€?鏂囨。鍚屾
`IMPLEMENTATION_LOG.md` 宸插埌 v1-19锛沗HANDOFF.md` 宸插埌 v1-18 + 鏈枃浠讹紱姣忔瀹炴満缁撹閮借琛?`build/evidence/v1-XX/`銆?
---

## 7. 鐜涓庢搷浣滃潙锛堣娉紝鍔″繀娌跨敤锛?
1. **鏋勫缓蹇呴』璧板叡浜攣**锛歚& E:\MAC\.dsh-tmp\mca-locked.ps1 -Kind build -Log <path> -GradleArgs '--no-daemon','--max-workers=2',...`銆傜洿鎺ヨ皟 `gradlew.bat` 浼氫笌鍏朵粬 lane 鍐茬獊锛涙湰鏈哄唴瀛樼揣寮狅紝鍏ㄩ噺 Kotlin 鏋勫缓鏇?OOM锛坄--max-workers=2` + `--no-daemon` 鍙紦瑙ｏ級銆?2. **`am instrument` 蹇呴』甯?`-e class`**锛氬惁鍒欎細璺戞暣涓?APK 鐨?76 涓祴璇曪紙鍏朵腑缈昏瘧娴嬭瘯浼?assumption 澶辫触锛岃瀵煎垽鏂級銆?   姝ｇ‘锛歚-e class com.muyuchat.mca.video.VideoNativeBudgetDeviceTest`銆?3. **prompt 涓嶈甯︾┖鏍?*锛圥owerShell鈫抋db 浼氭媶鍙傚鑷?`am` 鐢ㄦ硶閿欒锛夛紝鐢ㄥ崟 token 鎴?`cmd /c` 鏁翠覆閲嶅畾鍚戙€?4. **logcat 钀界洏鐢?`cmd /c "adb 鈥?> file"`**锛屼笉瑕?`Start-Process -RedirectStandardOutput`锛堟湰鏈?`NO_PROXY`/`no_proxy` 閲嶅鐜鍙橀噺浼氳瀹冪洿鎺ュけ璐ャ€佷骇鍑?0 瀛楄妭锛夈€?5. **MIUI Greeze 浼氬喕缁撴棤鍙绐楀彛鐨?instrumentation**锛坄frozen uid = 10451 reason=check binder`锛岄樆濉炲湪 `VideoModelAssetVerifier.sha256Of` 鐨?JNI digest锛夈€傝閬匡細鍏?`am start` 涓荤晫闈㈠苟淇濇寔鍓嶅彴 + 杩愯鏈熼棿姣?~20s `input tap`銆?6. **鍙栬瘉鎸夎澶?mtime 鎺掑簭鎵?run 鐩綍**锛氭湰鏈烘椂閽熸浘琚烦鍒?`2026-11-01`锛屾寜鍚嶅瓧鎺掑簭浼氭嬁閿欑洰褰曘€?7. **瀹為獙鍓嶅厛鐪嬭姹傛棩蹇楅噷鏈夋病鏈夋湰娆℃柊澧炲瓧娈?*锛堝 `lora=`銆乣vae_tiling=`锛夈€傛浘鍦ㄥ埆浜烘瀯寤虹殑鏃?`.so` 涓婅窇锛岃鍒?LoRA 鏃犳晥"銆?8. **PowerShell 鍐欐枃浠跺潙**锛歚@"鈥?@` 鍙屽紩鍙?here-string 浼氭妸 C++ 鐨?`""` 涓庡弽寮曞彿杞箟鍚冩帀锛涙敼鏂囦欢鍚庡繀椤昏鍥炵‘璁わ紙鏇炬妸 `body.value("x")` 鍐欐垚 `body.value(""x"")` 瀵艰嚧缂栬瘧澶辫触锛夈€俙.NET` 鐨?`[IO.File]` 鐢?*缁濆璺緞**锛堝惁鍒欒惤鍒拌繘绋?CWD锛夈€?9. **纾佺洏**锛欵: 鏄富瑕佸伐浣滅洏锛堢幇浣?34.8 GB锛夛紱`.tmp-models` 閲岃繕鏈?`Huihui-Qwen3.5-4B-Q4_K_M.gguf`(2.4GB) 绛変粬浜鸿祫浜э紝鍕垮垹銆?
---

## 8. 璁惧鐘舵€侊紙浜ゆ帴鏃讹級

| 璁惧 | 鍨嬪彿/SoC | 鐘舵€?|
|---|---|---|
| `device-a` | 2304FPN6DC / SM8550 / Android 16 / HTP V73 / 54GB 鍙敤 | 涓変欢濂?+ LoRA 宸插氨浣嶏紱娴嬭瘯 APK 宸茶锛涢粯璁ら攣灞忥紙闇€浜哄伐瑙ｉ攣锛夛紱娓╁害 ~31鈥?0 掳C |
| `device-b` | 25091RP04C / SM8750P / Android 17 / HTP V79 / ~6GB 鍙敤 | 涓変欢濂?+ LoRA 宸插氨浣嶏紱鏂?base APK 宸茶锛沗/data/local/tmp` 鏇炬竻鐞嗚吘绌洪棿锛涢粯璁ら攣灞?|

涓ゆ満鍧囦负**瀹夊叏閿佸睆锛圥IN/鎸囩汗锛?*锛岃嚜鍔ㄥ寲**涓嶅緱灏濊瘯缁曡繃**锛涙瘡娆″疄鏈哄墠闇€浜哄伐瑙ｉ攣骞朵繚鎸佷寒灞忋€?
---

## 9. 澶嶇幇鍛戒护閫熸煡

```powershell
# 鏋勫缓锛堝叡浜攣锛?& E:\MAC\.dsh-tmp\mca-locked.ps1 -Kind build -Log E:\MAC\build\evidence\v1-XX\build.log `
  -GradleArgs '--no-daemon','--max-workers=2','-PmcaDeviceTestVariant=true',':app:assembleDeviceTest',':app:assembleDeviceTestAndroidTest'

# 鎺㈤拡锛堝彧璺戠洰鏍囩被锛涘甫 LoRA锛?adb -s <serial> shell am instrument -w -e class com.muyuchat.mca.video.VideoNativeBudgetDeviceTest `
  -e videoBudgetProbe true -e width 128 -e height 128 -e frames 9 -e steps 4 -e prompt ball `
  -e loraPath /data/local/tmp/wan_lora/Wan22_TI2V_5B_Turbo_lora_rank_64_fp16.safetensors -e loraWeight 1.0 `
  com.muyuchat.mca.devicetest.test/androidx.test.runner.AndroidJUnitRunner

# 鍙栬瘉锛堟寜 mtime 鎵炬渶鏂?run锛?adb -s <serial> shell run-as com.muyuchat.mca.devicetest ls -l files/video/runs
cmd /c "adb -s <serial> exec-out run-as com.muyuchat.mca.devicetest cat files/video/runs/<dir>/probe-frame-0.png > E:\MAC\build\evidence\v1-XX\frame0.png"
```

---

## 10. 纭害鏉燂紙涓嶅彲杩濆弽锛?
- 鍏变韩宸ヤ綔鍖猴細**淇濈暀鎵€鏈夋棦鏈夋敼鍔紝涓?reset / 涓?clean / 涓嶈鐩栧叾浠栦换鍔★紱涓嶆彁浜ゃ€佷笉鎺ㄩ€?*銆傚綋鍓?HEAD `68b6eef`銆乻taged 0銆?- **涓嶅緱鍙戝竷** `AGENTS.md` 涓?`app/src/test/java/com/muyuchat/mca/UniversalDeviceAdmissionPolicyTest.kt`銆?- **涓嶅緱鎸夎姱鐗?璁惧鐧藉悕鍗曢殣钘?*涓嬭浇銆佸鍏ユ垨杩愯鍏ュ彛锛涙湭鐭ヨ澶囪蛋閫氱敤璺緞锛岀敱鐪熷疄杩愯鍒ゅ畾銆?- **涓嶅緱**鎶?64脳64/128脳128 瀹為獙缁撴灉鎴栨帰閽堥€氳繃鍐欐垚浜у搧閫氳繃锛?*涓嶅緱闈欓粯鏀归粯璁よ鏍兼垨棰勭畻**锛堥粯璁や粛鏄?480脳832脳33 / 16fps / 20 steps锛夈€?- 璁惧娓╁害 >42 掳C 蹇呴』鍋滄锛涘疄鏈哄墠璁板綍婧愮爜/瀛愭ā鍧?APK/妯″瀷鍝堝笇锛岀‘淇濈粨璁虹粦瀹氬綋鍓嶈緭鍏ャ€?
---

## 11. 浜ゆ帴鍚庣涓€浠朵簨锛堝缓璁級

1. 澶嶆牳 搂1 鐨勫叓缁寸粨璁烘槸鍚︿笌鏈€鏂版簮鐮?璇佹嵁涓€鑷达紙涓嶈鐓ф妱鏈枃锛夛紱
2. 澶勭悊 P0 涓嬭浇锛堜笁閫変竴锛夛紝鎷垮埌 20GB 鍚庢寜 搂6-P1 璧拌浆鎹紱
3. 鑻ユ殏鏃舵棤娉曚笅杞斤紝鍏堝仛 搂6-P2 鐨?TalkBack 涓庡浘鐗囦徊瑁佷覆琛岃瘉鏄庯紝鎶婁换鍔?4/6 鐨?NOT_RUN 娓呮帀銆?
---

# 杩藉姞锛?026-10-30 sdcpp_external/Hexagon 璺嚎瀹炴祴缁撹锛坴1-23锛?
## 鐜锛堝叏閮?pinned銆佸彲澶嶇幇锛?- sdcpp_external sdcpp 63954dc794f6daade32c407da1a9c4bc2d73cf0 + ggml 8758628270072da26ad44baf5e189d4695d9800
- Hexagon SDK **6.7.0.1**锛圱ools 21.0.04锛塃:\tools\hexagon-sdk-extract\6.7.0.0
- Android NDK **r29**锛?9.0.14206865锛塃:\tools\ndk-linux\android-ndk-r29锛圠inux锛屾瀯寤哄湪 WSL Ubuntu-22.04 鍐咃級
- CMake **4.4.4**锛坧ip 瀹夎锛泂kel 瀛愬伐绋嬭姹?鈮?.22.2锛?- 鏋勫缓鐩綍锛欵:\MAC\.dsh-tmp\sdcpp_external-build锛堥殧绂伙紝涓嶆敼 MCA 瀛愭ā鍧楋級
- 浜х墿锛?in/sd-cli 11,707,488 B銆?in/libstable-diffusion.so 162,644,640 B銆?  libggml-htp-v73.so 853,960 B銆乴ibggml-htp-v79.so 930,472 B

## 蹇呴』鎵撶殑琛ヤ竵
1. **5D GGUF 鍫嗗彔**锛坓gml/src/gguf.cpp锛夛細sdcpp_external 寮曟搸**娌℃湁**鎴戜滑鐨勪慨澶嶏紝涓嶇Щ妞嶅垯瀹屽叏璇讳笉浜?Wan 2.2 TI2V-5B銆?   绉绘鍚庢棩蹇楋細stacked 5D shape into 4D (2,2,1,147456)銆乂ersion: Wan 2.2 TI2V銆乸atch_embedding_channels 147456銆?2. **UMT5 璇嶈〃**锛氳鏍?*鏃犻渶**琛ヤ竵锛?ocab.cpp 鏃?Android 绌鸿繑鍥炲垎鏀級銆?
## 杩愯閰嶇疆锛堝叧閿級
`
-M vid_gen --backend diffusion=HTP0,vae=HTP0,te=cpu \
--params-backend disk --offload-to-cpu --max-vram 2 \
--steps 4 --width 832 --height 480 --video-frames 1 --seed 42 -p "..."
`
- --backend 蹇呴』**鏄惧紡鎸囧畾**锛屽惁鍒?ackend_fit 闈欓粯鍥為€€ CPU锛?- --params-backend disk 鏄?device-b锛?1.6 GB锛変笉 OOM 閲嶅惎鐨?*蹇呰鏉′欢**锛?- --offload-to-cpu --max-vram 2 鐢ㄤ簬缁曞紑澶у潡 fastrpc 鏄犲皠澶辫触銆?
## 瀹炴祴锛?8a / SM8750P / HTP v79锛?| 閰嶇疆 | 閲囨牱 | 瑙ｇ爜 | 缁撴灉 |
|---|---|---|---|
| 832脳480脳1锛寁ae=HTP0 | 128.58 s | **8.26 s** | **绾㈢悆+钃濆簳锛屼富浣撲笌鎻愮ず璇嶄竴鑷?* |
| 832脳480脳1锛寁ae=CPU | 128.65 s | 308.61 s | 鍚屼笂锛圕PU 鎱?37脳锛?|
| 832脳480脳5锛寁ae=HTP0 | 鈥?| 澶辫触 | wan_vae ... failed during weight preparation |
| 832脳480脳9锛寁ae=HTP0 | 鈥?| 澶辫触 | 鍚屼笂锛涜嚜鍔?tiling 閲嶈瘯浜﹀け璐?|
| 832脳480脳5锛寁ae=HTP0锛?-max-vram 4 | 鈥?| **SIGABRT(134)** | 棰勭畻璋冨ぇ鍙嶈€屽穿婧?|
| 256虏脳1/脳5锛圕PU 瑙ｇ爜锛?| 40.0/53.4 s | 50.9/214.6 s | 鏈夋晥锛堟娊璞★級 |
| 256虏脳9锛圕PU 瑙ｇ爜锛?| 68.58 s | 382.80 s | **绾櫧** |
| 128虏脳9锛圕PU 瑙ｇ爜锛?| 36.85 s | 93.17 s | 鏈夋晥 |

## 缁撹
- **DiT 閲囨牱鍦?HTP 涓婂畬鍏ㄥ彲鐢?*锛坴73+v79锛岃法鏋舵瀯涓€鑷达紝鏃?NaN锛夛紱
- **VAE 瑙ｇ爜鍦?v79 涓婂彲鐢ㄤ笖鏋佸揩**锛堝崟甯?480p 8.26 s锛夛紝浣?*澶氬抚 VAE 鍥捐秴鍑哄彲鏄犲皠瀹归噺**锛坵eight-prep 闃舵澶辫触锛岃皟澶ч绠椾細鐩存帴 abort锛夛紱
- **CPU 瑙ｇ爜鐨勬湁鏁堝寘缁?鈮?0.4M 鍍忕礌**锛圵脳H脳甯э級锛?-vae-tiling/--temporal-tiling 鍙檷 6% 缂撳啿銆佷笉鑳界獊鐮达紱
- **480p 鍗曞抚涓讳綋璐ㄩ噺 PASS**锛屼絾 **480p 瑙嗛锛堚墺5 甯э級褰撳墠涓嶅彲琛?*銆?
## 鍓╀綑宸ヤ綔锛堢害 2 鍛級
1. VAE 澶氬抚瑙ｇ爜鏀归€狅紙閫愬抚/閫愬潡/TAEHV锛夆€?3鈥? 浜烘棩锛?2. G4 480p 1 s / 5 s 楠屾敹 鈥?0.5鈥? 浜烘棩锛?3. 鎺ュ洖 :local_video worker锛堟部鐢?VideoGenerationOwner + NativeMediaExecutionGate锛夆€?2鈥? 浜烘棩锛?4. G6 鐢熶骇閾撅紙UI + Local API + 鍙戝竷/鍙栨秷/鎭㈠锛夆€?2鈥? 浜烘棩銆?
## 璇佹嵁
uild/evidence/v1-23/锛歅HASE_SUMMARY.md銆乨ecode-envelope.md銆丗IRST_VALID_480P_FRAME.md銆?un98-{2..12}.log銆?79-480p-f0.png銆?79-480p-htpvae.png銆?79-256.png銆?79-5f0.png銆?79-128f0.png銆佸悇 AVI銆?## 琛ュ厖锛氬喅瀹氭€у垽瀹氾紙2026-10-30 10:41锛?
宸蹭笅杞藉苟鏍￠獙 **UMT5 Q4_K_M**锛坲mt5-xxl-encoder-Q4_K_M.gguf锛?,655,145,312 B锛屾瘮 Q8_0 鐪?2.39 GB锛夛紝鎺ㄥ埌 device-b 鍚庝互瀛楄妭鏍￠獙閫氳繃銆傜敤瀹冧笌 ae=HTP0 閲嶈窇 **832脳480脳5**锛?
`
[ERROR] wan_vae segment 1/1 (graph) failed during weight preparation
[ERROR] decode_first_stage failed for video     (exit=1)
`

**缁撹锛堝凡鎺掗櫎鍐呭瓨鍙橀噺锛?*锛?80p 澶氬抚澶辫触涓庡唴瀛樻棤鍏筹紱鏄?**Wan VAE 鐨勩€岃棰戣В鐮佸瓙鍥俱€嶆棤娉曞湪 HTP 鍚庣鍑嗗/鏄犲皠**锛堝浘鍍忚В鐮佸瓙鍥炬甯革紝8.26 s vs CPU 308.61 s锛夈€傚洜姝ら樁娈?5 蹇呴』鏀?*瑙ｇ爜璺緞**锛堥€愬抚/閫愬潡 + 娴佸紡钀界洏锛屾垨 TAEHV 瑙ｇ爜鍣級锛屼粎璋冨唴瀛橀绠楁棤鏁堛€傚伐鏃剁淮鎸?3鈥? 浜烘棩銆?
璇佹嵁锛?uild/evidence/v1-23/decode-final-conclusion.md銆?un98-15.log銆乽mt5 Q4_K_M 鎺ㄩ€佹牎楠岃褰曘€