# MCA 鍘熺敓瑙嗛宸ヤ綔娴侊細瀹炴柦璁板綍

瀹炴柦鏃ユ湡璧凤細2026-10-25銆傛湰鏂囦欢鏄?[TASK_PACK.md](TASK_PACK.md) 鍚勫崱鐨勬鏌ョ偣璁板綍锛屾寜纭攣 搂16 浜や粯璇佹嵁鏍煎紡閫愬崱杩藉姞銆傛墍鏈?`PASS` 浠呰鐩栧綋鍗″０鏄庣殑楠岃瘉鑼冨洿锛沶ative/璁惧/鍙戝竷楠屾敹鍦ㄥ搴斿崱瀹屾垚鍓嶄繚鎸?NOT_RUN銆?
---

## V0-01 鍩虹嚎涓庤竟鐣屽喕缁?
```text
card: V0-01
start_revision: 68b6eef1e67870b02bfef6afda2e625d36663da9 (branch codex/enterprise-refactor)
workspace_input_fingerprint: 607 鏉?porcelain 璁板綍锛?25 M / 280 ?? / 2 D锛夛紝鍏ㄩ儴涓烘棦鏈夋敼鍔紝闈炴湰浠诲姟浜х墿
existing_changes:
  鐖朵粨搴? 澶ч噺宸茶窡韪慨鏀?untracked锛坅pp/src 164 涓?untracked銆乫eature/chat 21銆乧ore/designsystem 17銆乨ocs/adr 9 绛夛級
  third_party/llama.cpp @ 4ceb1719101f32637b841206c172f3f058ffc182锛屽唴閮?20 涓剰鏂囦欢锛坓gml cpu/vulkan/quants锛?  third_party/stable-diffusion.cpp @ 16b3846acfdf2c9499a3ac0c3d5d2914ebe859a0锛屽唴閮?18 涓剰鏂囦欢
    锛坰table-diffusion.h/model.h/model.cpp/stable-diffusion.cpp/vae.hpp/conditioner.hpp/denoiser.hpp/
     diffusion_model.hpp/llm.hpp/name_conversion.cpp/tokenizers/*锛屽彟鍒犻櫎 libwebm 瀛愮洰褰曚笌 server/frontend 閾炬帴锛?  third_party/tokenizers-cpp @ acbdc5a27ae01ba74cda756f94da698d40f11dfe锛屽唴閮?5 涓剰鏂囦欢
actual_write_set: 浠呮湰鏂囦欢锛坉ocs/proposals/mca-native-video-workflow-v1/IMPLEMENTATION_LOG.md锛?result: PASS锛堝彧璇绘牳鏌ワ紝鑼冨洿瑙佷笅锛?rollback_point: 鍒犻櫎鏈枃浠跺嵆鍙紱鏈Е纰扮敓浜ф簮鐮佷笌鏋勫缓杈撳叆
next_card: V0-01b锛堝墠缃弧瓒筹細瀹炵幇鎺堟潈宸插湪浼氳瘽涓幏寰楋級
```

### 宸ュ叿閾句笌鍩虹嚎鏍稿锛?026-10-25 瀹炴祴锛?
| 椤圭洰 | 瀹炴祴浜嬪疄 | 涓庢柟妗堝熀绾垮叧绯?|
| --- | --- | --- |
| HEAD | `68b6eef1e67870b02bfef6afda2e625d36663da9`锛屽垎鏀?`codex/enterprise-refactor` | 涓?EXECUTION_PLAN 搂2.5 / REFERENCE_AUDIT 搂4 鐩稿悓锛屾枃妗ｅ熀绾挎湭婕傜Щ |
| stable-diffusion.cpp | `16b3846acfdf2c9499a3ac0c3d5d2914ebe859a0` + 鍐呴儴琛ヤ竵 | 涓庡璁¤褰曚竴鑷达紱鍐呴儴琛ヤ竵鏄瀯寤鸿緭鍏ワ紝V1-03 鍓嶉』瀹屾暣璁板綍 diff |
| tokenizers-cpp | `acbdc5a...` + 5 涓剰鏂囦欢 | D08 鍊哄姟浠嶅湪锛屾湭鎵╁紶 |
| JDK | PATH java=17锛沗JAVA_HOME=E:\model\toolchains\jdk-21\jdk-21.0.12.1+1` | Gradle 璧?JAVA_HOME锛屾弧瓒?JDK 21 瑕佹眰锛涗笉渚濊禆 PATH java |
| Android SDK | `D:\model\android-sdk`锛宲latforms android-35/36锛孨DK 29.0.13113456 / 29.0.14206865 | 婊¤冻 minSdk26/targetSdk36 涓?native 鏋勫缓鏉′欢 |
| Room | `ChatSessionStoreRoomDatabase.kt` version=29 | 涓庢柟妗堜竴鑷达紱V1-02a 鍓嶉』閲嶈閫掑锛屼笉鐩插啓 30 |
| 鏂规鐩綍 | `docs/proposals/mca-native-video-workflow-v1/` 涓?untracked锛屽惈 3 浠芥枃妗?| 鏈崱鏂板绗?4 浠借褰曟枃浠讹紝鍚岀洰褰?|

### 婧愮爜浜嬪疄涓庢柟妗堝鐓э紙閫愰」瀹炴祴纭锛?
| 鏂规鏂█ | 瀹炴祴 | 缁撹 |
| --- | --- | --- |
| AppTab 鏃?VIDEO锛孌SH_WORKSPACE 杩藉姞鍦ㄦ渶鍚?| AppTab.kt锛欳HAT/AGENT/MODELS/API/SETTINGS/AGENT_WORKSPACE/DSH_WORKSPACE | 涓€鑷达紱VIDEO 杩藉姞鍦?DSH_WORKSPACE 涔嬪悗锛屼笉閲嶆帓 |
| ChatShellActions 鏈?onOpenAgentWorkspace/onOpenDshWorkspace | MainChatScreenActions.kt:245-259 | 涓€鑷达紱鏂板 onOpenVideoWorkspace 璧板悓涓€妯″紡 |
| 渚ф爮椤哄簭 鍥剧墖鈫扐gent鈫扗SH鈫掓渶杩?| ChatHistoryDrawerScreens.kt:382-410 | 涓€鑷达紱鈥滆棰戔€濇彃鍦?DSH 涓庢渶杩戜箣闂?|
| sd_vid_gen_params_init + generate_video ABI | stable-diffusion.h:705-710 瀹屽叏涓€鑷达紙frames_out/num_frames_out/audio_out 鍑哄弬锛?| 涓€鑷达紱V1-03 鎸夋 ABI 鏄犲皠 |
| 甯ф暟瀵归綈 4k+1 | stable-diffusion.cpp:3277-3309 `align_video_frames` 缁?latent 鎹㈢畻锛沬nit 榛樿 video_frames=6 | 涓€鑷达紱6鈫? 瑙勫垯鐪熷疄瀛樺湪锛宑ompiler 蹇呴』鏄惧紡 resolved |
| decode_video_outputs 鍏ㄩ噺 float tensor鈫掑叏閲?RGB | stable-diffusion.cpp:7040+ 纭涓ら樁娈靛叏閲忓垎閰?| 涓€鑷达紱宄板€奸闄╂垚绔嬶紝涓嶅緱瀹ｇО甯搁噺鍐呭瓨 |
| cancel 鍥炶皟涓?DSO 鍏ㄥ眬 | stable-diffusion.h:636 `sd_set_cancel_callback` | 涓€鑷达紱杩涚▼闅旂 + 涓茶瀹夎鏄繀瑕佽璁?|
| NativeStableDiffusionBridge 鏃?video 鏂规硶 | 浠呮湁 generate/upscale/getSystemInfo/getProgress/getNativeConfig/measurePromptTokens/cancel/shutdown | 涓€鑷?|
| MNN runVideo 鍗犱綅 | mnn_diffusion_generate_entries.cpp:120 瀛樺湪浣嗘寜鏂规璁板綍涓嶅彲鐢?| 涓€鑷?|
| CMake 鍏抽棴 SD_WEBP/SD_WEBM/SD_VULKAN/GGML_VULKAN | CMakeLists.txt:16-45 澶氬 FORCE OFF | 涓€鑷达紱浠?CPU 璺緞 |
| 鍥剧墖鏂囦欢閿?native_image_execution.lock | LocalImageExecutionGate.kt:16锛汱ocalImageWorkerExecution.kt:47/253 宸叉帴鍏?| 涓€鑷达紱V1-00 鎺ㄥ箍姝ら攣鍩?|

### 鏈疄鐜伴攣瀹氱殑杈圭晫

- **淇濈暀鑳藉姏**锛氱幇鏈?607 鏉″伐浣滃尯鏀瑰姩鍏ㄩ儴淇濈暀锛涗笉 reset/clean/checkout锛涘瓙妯″潡鍐呴儴琛ヤ竵瑙嗕负鏋勫缓杈撳叆銆?- **鍐欓泦绾緥**锛氭瘡鍗″紑宸ュ墠鍒楃簿纭啓闆嗭紱鍏变韩鏂囦欢锛坰ettings.gradle.kts銆乴ibs.versions.toml銆丮ainActivity銆乧omposition銆丷oom schema锛夌敱鏁村悎璐熻矗浜猴紙鏈細璇濅富 Agent锛夌嫭鍗犲啓鍏ャ€?- **楠岃瘉鍏ュ彛**锛歚scripts/verify-code.ps1`銆乣scripts/build.ps1`銆乣tools/architecture/check_architecture_rules.py`锛涙瀯寤轰覆琛岋紝涓嶅苟鍙?Gradle/native銆?- **鏋勫缓杈撳叆璁板綍**锛歏1-03 鏃跺繀椤昏ˉ stable-diffusion.cpp 鍐呴儴琛ヤ竵鐨勫畬鏁?diff 娓呭崟锛堝綋鍓嶄粎璁版枃浠跺悕锛夈€?
### V0-01 鏈獙璇侀」

- 鏃狅紙鏈崱涓哄彧璇绘牳鏌ワ紱涓嶆瀯寤恒€佷笉杩愯娴嬭瘯锛岀鍚堝崱瀹氫箟锛夈€?
---

## V0-01b 濂戠害瀹炶涓庢鏌ユ帴绾?
```text
card: V0-01b
start_revision: 68b6eef1e67870b02bfef6afda2e625d36663da9锛堝悓 V0-01锛屾湰鍗℃湭浜х敓鎻愪氦锛?actual_write_set:
  settings.gradle.kts                     鈥斺€?杩藉姞 :core:video-contract銆?feature:video-workspace 涓や釜 include锛堣拷鍔犲紡锛屼笉鍔ㄦ棦鏈夋ā鍧楋級
  core/video-contract/build.gradle.kts    鈥斺€?鏂版ā鍧楁瀯寤鸿剼鏈紙Android library锛屼豢 dsh-contract锛沘pi kotlinx-coroutines-core + junit 娴嬭瘯锛?  core/video-contract/src/main/.../contract/{VideoWorkflowIds,VideoNodeTypes,VideoWorkflowDocument,
    VideoWorkflowValidation,VideoWorkflowParamValidation,VideoWanBaselineRules,VideoWorkflowCanon,
    VideoRunStates,VideoRunSnapshot,VideoErrorCodes,VideoWorkflowPort}.kt 鈥斺€?濂戠害婧愮爜锛?1 鏂囦欢锛?  core/video-contract/src/test/.../contract/{TestVideoGraphs,VideoWorkflowValidationTest,
    VideoWorkflowCanonTest,VideoRunTransitionsTest,VideoContractValueTest}.kt 鈥斺€?娴嬭瘯锛? 鏂囦欢锛?5 娴嬭瘯锛?  feature/video-workspace/build.gradle.kts 鈥斺€?绌?feature 妯″潡鏋勫缓鑴氭湰锛圕ompose 鎺ョ嚎浠?dsh-workspace锛涗粎渚濊禆 video-contract + designsystem锛?  scripts/verify-code.ps1                  鈥斺€?缂栬瘧 lane 杩藉姞涓や釜 video 浠诲姟锛涙柊澧?-VideoTests 閫氶亾锛堜簰鏂ユ牎楠?缁撴灉鐩綍锛夛紱-FullTests/-Changed 鏄犲皠杩藉姞 video 妯″潡
  tools/architecture/check_architecture_rules.py 鈥斺€?鏂板 Rule 18锛坴ideo 濂戠害绾害锛?Rule 19锛坴ideo workspace 杈圭晫锛夛紱LOC 涓婇檺琛?+ LF 妫€鏌ョ撼鍏?video 濂戠害鐩綍
production_callers: 灏氭棤锛堝绾︽ā鍧楁寜璁捐绛夊緟 V0-02 瑁呴厤锛沠eature 绌?module 缂栬瘧閫氳繃鍗虫湰鍗″嚭鍙ｏ級
owner_and_state_writer: 鏃犺繍琛屾椂鐘舵€併€俈ideoRunTransitions 琛ㄦ槸鍞竴鐘舵€佹潈濞佸畾涔夛紙owner 鍦?V1-02a 钀藉湴锛?commands:
  python tools/architecture/check_architecture_rules.py          鈫?exit 0锛孉LL 19 RULES PASSED
  gradlew锛堥攣鍐呭畾鍚戯級: :core:video-contract:compileDebugKotlin + :testDebugUnitTest --rerun
    鈫?BUILD SUCCESSFUL in 2m 4s锛宔xit 0
  gradlew锛堥攣鍐呭畾鍚戯級: :feature:video-workspace:compileDebugKotlin + :testDebugUnitTest
    鈫?BUILD SUCCESSFUL in 35s锛宔xit 0锛堢┖ feature 鏃犳祴璇曟簮闆嗭級
test_count锛圝Unit XML 瀹炴祴锛? tests=45 failures=0 errors=0 skipped=0
  VideoWorkflowValidationTest 27 / VideoWorkflowCanonTest 7 / VideoRunTransitionsTest 7 / VideoContractValueTest 4
result:
  濂戠害+妫€鏌ユ帴绾? PASS锛堟ā鍧楃紪璇戙€?5 娴嬭瘯銆?9 鏉℃灦鏋勮鍒欏叏杩囷紝鎶ュ憡涓烘湰娆¤繍琛?XML锛?  scripts/verify-code.ps1 -VideoTests 鍏ㄩ€氶亾: BLOCKED锛堝閮ㄦ潯浠讹紝瑙佷笅锛?unverified_or_blocked:
  - 鍏ㄩ€氶亾 verify锛坈ompile lane 鍚?:app鈫?core:dsh-runtime锛夎骞惰缂栬緫鑰呴樆鏂細core/dsh-runtime/
    鏁寸洰褰曚负浠栦汉 untracked 杩涜涓伐浣滐紝DshJobRecovery.kt 浜?16:34锛堥獙璇佽繍琛屾湡闂达級琚閮ㄤ慨鏀癸紝
    缂栬瘧鎶?mid-edit 閿欒涓斾袱娆¤Е鍙?input-changed-during-run銆傛仮澶嶆潯浠讹細宸ヤ綔鍖虹ǔ瀹氬悗閲嶈窇
    .\scripts\verify-code.ps1 -VideoTests -Rerun銆?  - 鐪熸満娓叉煋/鏃犻殰纰嶏細NOT_RUN锛堟湰鍗℃棤 UI锛夈€?rollback_point: 鍒犻櫎涓や釜鏂版ā鍧楃洰褰?+ 杩樺師 settings.gradle.kts/verify-code.ps1/check_architecture_rules.py 鐨勬湰鍗?diff锛涗笉瑙︾浠讳綍鏃㈡湁涓氬姟鏂囦欢
next_card: V0-02锛堝墠缃?V0-01b 婊¤冻锛氬绾︽ā鍧楀瓨鍦ㄤ笖閫氳繃娴嬭瘯锛沠eature 绌哄３鍙寕 Route锛?```

### 濂戠害鍐呭鎽樿锛堜笌涓绘柟妗?搂4 鐨勫搴旓級

- **versioned graph**锛歚VideoWorkflowDocument/VideoNode/VideoEdge`锛宻chemaVersion=1锛涜妭鐐圭被鍨嬫敞鍐岃〃 `VideoNodeTypeRegistry` 鍥哄畾 8 绫?V1 鍘熺敓鑺傜偣锛坕nput_text/input_image/wan22_ti2v_5b_model/wan_conditioning/wan_sampler/wan_decode/encode_mp4/save_asset锛夛紝姣忕被甯?typeVersion銆乼yped 绔彛澹版槑锛? 绉?`VideoPortKind`锛変笌鍙傛暟 schema銆?- **鍥炬牎楠?*锛歚validateVideoWorkflow` 瑕嗙洊閲嶅 ID銆佹湭鐭ョ被鍨?鐗堟湰銆佺己澶辩鐐广€佺鍙ｄ笉瀛樺湪銆佺被鍨嬩笉鍖归厤銆佸瓧闈㈤噺/杩炵嚎浜掓枼銆佸繀濉己澶便€佹湭鐭ュ弬鏁般€佹暟鍊艰秺鐣屻€佽嚜鐜?鐜€佸崟杈撳嚭鏍癸紱閿欒閿氬畾 nodeId/port 骞跺甫閿欒鐮併€?- **Wan 鍩虹嚎瑙勫垯**锛坄VideoWanBaselineRules`锛屾簮鑷?native `align_video_frames` 瀹炴祴鎺ㄥ锛夛細甯ф暟蹇呴』 `4k+1`锛? 浼氳 native 缂╁埌 5锛岀紪璇戝墠鏄惧紡鎷掔粷骞跺洖鏄?resolved 鍊硷級锛涘楂樺繀椤?32 鐨勫€嶆暟銆?- **canonical form**锛歚VideoWorkflowCanon` 鑷湁纭畾鎬?writer锛堥敭鎺掑簭銆佽妭鐐?杈规帓搴忋€佸畾鐐瑰皬鏁般€佸浐瀹氳浆涔夛級锛孶I/metadata/rawSourceRef 涓嶈繘 digest锛泂eed/prompt/鍑犱綍/閲囨牱/fps 鍙樺寲蹇呮敼 digest锛堟湁娴嬭瘯锛夈€?- **run/attempt 韬唤**锛? 涓?value-class id锛屾牸寮忎负鍐荤粨濂戠害锛沗VideoRunTransitions` 閫愬瓧鍐荤粨 搂4.3.1 杞崲琛紙娴嬭瘯鍏ㄦ灇涓撅紝鍚?UNKNOWN/RECONCILING 鎭㈠闆嗐€佺粓鎬佹棤鍑鸿竟銆佹棤鍐掕繘 CANCELLED锛夈€?- **snapshot**锛歚VideoRunSnapshot`锛坮evision digest+杈撳叆缁戝畾+requested/resolved/nativeEffective 涓夋鍙傛暟+妯″瀷/ profile fingerprint+backend锛夛紱`VideoResolvedParams` init 寮哄埗鍑犱綍/甯?閲囨牱涓嶅彉閲忋€?- **閿欒鐮?*锛毬?.4 鍏ㄩ噺 25 椤?`VideoErrorCode`銆?- **绐?port**锛歚VideoWorkflowPort` 5 椤硅兘鍔涳紙observeRuns/observeRun/submit/requestCancel/retry锛? 鍛戒护/缁撴灉/鍙鎶曞奖銆?
### 妫€鏌ユ帴绾胯瘉鎹?
- `verify-code.ps1`锛歝ompile lane 鐜板惈 `:core:video-contract:compileDebugKotlin`銆乣:feature:video-workspace:compileDebugKotlin`锛沗-VideoTests` 鏂伴€氶亾杩愯涓ゆā鍧?testDebugUnitTest 骞惰仛鍚堢粨鏋滅洰褰曪紱`-FullTests` 涓?`-Changed` 璺緞鏄犲皠宸茬撼鍏ャ€?- `check_architecture_rules.py`锛歊ule 18 绂?video 濂戠害寮?android/androidx/app/engine/sdnative/org.json/okhttp/room/compose锛汻ule 19 闄?feature:video-workspace 浠呬緷璧?`:core:video-contract`+`:core:designsystem` 涓旀簮鐮佺寮?runtime/engine/app锛泇ideo 濂戠害绾冲叆 300 琛?LOC 涓婇檺涓?LF 妫€鏌ワ紙棣栬窇鎶撳埌 411 琛岃秴闄愪笌 CRLF锛屽潎宸蹭慨澶嶁€斺€旈棬绂佹湁鏁堬級銆?- CI锛坅ndroid-ci.yml锛夛細鏃犻渶鏀瑰姩鈥斺€擿./gradlew testDebugUnitTest` 鍏ㄦā鍧椾换鍔″湪鏂版ā鍧楀叆 settings 鍚庤嚜鍔ㄨ鐩栵紱鏈櫄澧炲０鏄庛€?
---

## V0-02 瑙嗛鍏ュ彛澹?
```text
card: V0-02
start_revision: 68b6eef1e67870b02bfef6afda2e625d36663da9锛堟湭鎻愪氦锛屽悓鍓嶏級
actual_write_set:
  app/.../AppTab.kt                        鈥斺€?杩藉姞 VIDEO_WORKSPACE("瑙嗛")锛圖SH 涔嬪悗锛屼笉閲嶆帓鏃ф灇涓撅級锛涙柊澧炵函鍑芥暟 tabAfterLateModelLoad
  app/.../MainActivity.kt                  鈥斺€?DSH 鍧楀悗杩藉姞 VIDEO_WORKSPACE CrossfadeTabPage锛堢嫭绔?tabKey/SaveableStateHolder锛宱nDismiss 鍥?CHAT锛夛紝port=null 鏄惧紡鏈帴绾?  app/.../MainChatScreenActions.kt         鈥斺€?ChatShellActions 鎺ョ嚎 onOpenVideoWorkspace = host.onTab(VIDEO_WORKSPACE)
  app/.../LoadModelCompletionPublish.kt    鈥斺€?tab 淇濇寔閫昏緫鏀圭敤 tabAfterLateModelLoad锛圖SH 璇箟涓嶅彉锛屾柊澧炶棰戜繚鐣欙級
  app/.../ChatModelCancelAndSwitchOperations.kt 鈥斺€?鍚屼笂
  feature/chat/.../ChatScreenActions.kt    鈥斺€?ChatShellActions 澧?onOpenVideoWorkspace锛堥粯璁?{}锛岀獎鍥炶皟锛?  feature/chat/.../ChatScreen.kt           鈥斺€?鍙?shell 鍥炶皟骞朵紶缁欐娊灞夛紙瀵艰埅鍏堜簬鍏虫娊灞夛紝娌?Agent/DSH 娉ㄩ噴鐨勫疄娴嬮『搴忥級
  feature/chat/.../ChatHistoryDrawerScreens.kt 鈥斺€?DSH 涓?鏈€杩?涔嬮棿鎻掑叆"瑙嗛"AppFeatureButton锛圡caIcons.PlayArrow锛宼estTag=drawer.video_workflow锛宎11y=鎵撳紑瑙嗛宸ヤ綔鍖猴級
  feature/video-workspace/.../VideoWorkspaceRoute.kt 鈥斺€?鎷熸柊澧?Route锛圴0-03 宸插崌绾т负瀹屾暣椤甸潰锛?  app/src/test/.../AppTabLateModelLoadTest.kt 鈥斺€?鎷熸柊澧烇紙2 娴嬭瘯锛?  feature/chat/src/test/.../ChatHistoryDrawerVideoEntryContractTest.kt 鈥斺€?鎷熸柊澧烇紙2 娴嬭瘯锛?owner_and_state_writer: 鏃犺繍琛屾椂鐘舵€佹柊澧烇紱瑙嗛 tab 鍙粡 host.onTab 瀵艰埅锛涢〉闈笉鍚姩浠讳綍 native
production_callers: MainActivity 鈫?VideoWorkspaceRoute(port=null)锛圴1-02a 鎹负鐪熷疄 owner port锛?commands_and_results:
  閿佸唴瀹氬悜 gradlew :feature:video-workspace:compileDebugKotlin :feature:chat:compileDebugKotlin :app:compileDebugKotlin
    鈫?BUILD SUCCESSFUL in 15m 12s锛堥娆″洜骞惰 Agent 鎸侀攣涓?app/build 鍙ユ焺娈嬬暀涓ゆ澶辫触鍚庨€氳繃锛決NN SDK root 宸叉樉寮忔敞鍏ワ級
  閿佸唴 gradlew :feature:chat:testDebugUnitTest --rerun 鈫?exit 0
test_count锛堟湰娆¤繍琛?XML锛?5 鍒嗛挓绐楀彛鍐?fresh锛? 64 濂椾欢 / 484 娴嬭瘯 / 0 澶辫触 / 0 璺宠繃
  鍚柊澧?ChatHistoryDrawerVideoEntryContractTest: 2 娴嬭瘯 0 澶辫触
result:
  缂栬瘧: PASS锛坅pp+chat+video-workspace锛?  chat 鍥炲綊+鎶藉眽濂戠害: PASS锛?84/484锛?  app 杩囨护娴嬭瘯锛圓ppTabLateModelLoadTest 绛夛級: 瑙?V0-03 妫€鏌ョ偣锛堜笌鍚庣画鍙樻洿鍚堝苟楠岃瘉锛?  娴?娣辫壊鐪熸満娓叉煋 + 鏃犻殰纰嶅疄鏈? NOT_RUN锛堟棤杩炴帴璁惧锛涙仮澶嶆潯浠?鎺ュ叆璁惧鍚庤窇 V0-03 瑙嗚楠屾敹瑕嗙洊鏈叆鍙ｏ級
rollback_point: 鍥為€€鏈崱 9 涓枃浠剁殑 diff锛堟灇涓捐拷鍔犮€佸洖璋冦€佹寜閽€丷oute 鎸傝浇銆佷袱澶?tab 淇濇寔锛夛紝鏃у叆鍙ｅ叏閮ㄤ繚鐣欐湭鍔?next_card: V0-03锛堝墠缃弧瓒筹級
```

---

## V1-00 鎺ㄥ箍鏃㈡湁鍏变韩璧勬簮浠茶锛堥攣鍩熺瓑浠锋帹骞匡級

```text
card: V1-00
start_revision: 鍚屽墠
鍓嶇疆瀹¤锛堝彧璇伙紝鍏ㄩ儴瀹炴祴瀹氫綅锛?
  绗?1 灞?浜у搧璇锋眰韬唤/鍗?active: LocalImageGenerationCoordinator + 鍏变韩 RuntimeSupervisor锛堜笉鍙橈級
  绗?2 灞?鑱婂ぉ engine 鐢熷懡鍛ㄦ湡绉熺害: RuntimeReleaseOperations.prepareLocalImageRuntimeMemoryFor 鈫?engine.acquireExclusiveLifecycleLease锛堝浘鐗囪矾寰勬棦鏈夛紱瑙嗛缁?owner 澶嶇敤鍚屼竴鑳藉姏锛孷1-02a 鎺ワ級
  绗?3 灞?native 鐗╃悊浜掓枼: LocalImageExecutionGate 鏂囦欢閿?native_image_execution.lock
  鐗╃悊鎵ц鐐规灇涓? LocalImageWorkerExecution.kt:47(generate)/253(upscale) 涓ゅ withLease 鍖呰９鍏ㄩ儴
    provider 璺緞锛坰dcpp generate:219 / MNN generate:206 / upscale bridge:137 鍧囧湪鍧楀唴鎵ц锛夛紱
    app/src/debug/LocalImageSmokeActivity.kt 鍙︽湁 3 澶勫紩鐢紙debug 鍐掔儫鍏ュ彛锛夈€?    鏈彂鐜扮粫杩?gate 鐨勭敓浜?native 璋冪敤 鈥斺€?鍥剧墖渚ч攣鍩熸湰宸茬粺涓€锛屾棤闇€鏀瑰姩 coordinator/owners/API 璋冪敤鑰呫€?actual_write_set:
  LocalImageExecutionGate.kt 鈫?NativeMediaExecutionGate.kt锛坓it mv 鍥犲苟琛?Agent 鎸?index.lock 鏀逛负 Write+rm锛?    璇箟閫愬瓧鑺傜瓑浠凤細鍚岄攣鏂囦欢鍚?鍏钩杩涚▼绉熺害/鏂囦欢閿?鍙彇娑堢瓑寰咃紱浠呮洿鍚嶄笌鍏变韩鍩?KDoc锛涘紓甯哥被鍚屾鏇村悕锛?  LocalImageWorkerExecution.kt 鈥斺€?2 澶勫紩鐢ㄦ洿鍚?  app/src/debug/.../LocalImageSmokeActivity.kt 鈥斺€?3 澶勫紩鐢ㄦ洿鍚?  NativeMediaExecutionGate.kt 鏂板 File 鏍稿績绛惧悕 withLease(lockFile,...)锛圕ontext 閲嶈浇濮旀墭锛夛紝浣跨函 JVM 鍙祴
  app/src/test/.../NativeMediaExecutionGateConcurrencyTest.kt 鈥斺€?鎷熸柊澧烇紙3 娴嬭瘯锛氫覆琛屽寲/绛夊緟涓彇娑堜笉娉勭绾?椤哄簭浜ゆ帴锛?閿佸悎鍚? 閿佹枃浠跺悕 native_image_execution.lock 鍐荤粨涓嶆敼锛堟柊鏃ц繘绋嬪崌绾ф湡浜掓枼锛夛紱閿佸簭=浜у搧鍑嗗叆鈫掕亰澶╃绾︹啋鐗╃悊閿侊紙鏈彉锛屾湰鍗￠浂琛屼负鍙樺寲锛?commands_and_results: 缂栬瘧骞跺叆 V0-02 鐨?:app:compileDebugKotlin PASS锛涘苟鍙戞祴璇曚笌 app 杩囨护濂椾欢骞跺叆 V0-03 鍚庣粺涓€杩愯锛堣涓嬶級
result: 浠ｇ爜涓庢帴绾?PASS锛堢紪璇戠骇锛夛紱骞跺彂娴嬭瘯缁撴灉瑙?V0-03 妫€鏌ョ偣锛涜法杩涚▼鏂囦欢閿佷笌鎸佹湁杩涚▼姝讳骸閲婃斁=NOT_RUN锛堥渶 V1-02b 鐪熷弻杩涚▼锛屽凡璁版祴璇曟敞閲婏級
rollback_point: 杩樺師鏇村悕锛坓it 鐩镐技搴︿細璇嗗埆锛夛紱琛屼负闆跺彉鍖栨晠鍥為€€鏃犳暟鎹潰褰卞搷
next_card: V1-02b 鍓嶇疆鐨勯攣鍩熷凡灏辩华锛涜棰?worker 灏嗙洿鎺ヨ皟鐢?NativeMediaExecutionGate
```

---

## V1-01 鍩虹 TI2V-5B 妯″瀷娓呭崟涓庤祫浜ф牎楠岋紙P0-A锛?
```text
card: V1-01
start_revision: 鍚屽墠
actual_write_set:
  docs/proposals/mca-native-video-workflow-v1/MODEL_ASSET_SOURCES.md 鈥斺€?鎷熸柊澧烇細璧勪骇鏉ユ簮娓呭崟锛堝叏閮ㄦ暟鍊?2026-10-25 浠?HF API/CDN 瀹炲彇锛?  app/.../video/model/VideoModelManifest.kt 鈥斺€?鎷熸柊澧烇細manifest 绫诲瀷锛堣鑹查摼寮哄埗 dit+umt5+wan2.2 vae锛沵easuredPresets 寮哄埗涓虹┖寰?P0-C锛?  app/.../video/model/Wan22Ti2v5bProfiles.kt 鈥斺€?鎷熸柊澧烇細涓や釜鍥哄畾 profile锛圦4_0+Q8_0 涓婚€?/ Q4_K_M+Q4_K_M 澶囬€夛級+ 鍏变韩 VAE pin + 鍩虹嚎閰嶆柟锛坋uler/6.0/3.0/33甯?480x832锛屾簮=sd.cpp wan.md锛?  app/.../video/model/VideoModelAssetVerifier.kt 鈥斺€?鎷熸柊澧烇細瀹屾暣/缂轰欢/鎴柇/鍚屽悕閿欎欢/浠呭昂瀵竝in/纾佺洏浣欓噺鍏€佹牎楠岋紙sha256 娴佸紡澶嶆牳锛?  app/src/test/.../VideoModelAssetVerifierTest.kt 鈥斺€?鎷熸柊澧烇紙7 娴嬭瘯锛氬皬 fixture 瑕嗙洊鍏ㄩ儴鍒嗘敮 + 鐪熷疄 pin 浜嬪疄鏂█锛?  app/build.gradle.kts 鈥斺€?杩藉姞 :core:video-contract 涓?:feature:video-workspace 渚濊禆锛坅pp 浣滀负缁勫悎鏍癸級
asset_facts锛堝疄鍙栵級: 灏哄缁?HTTP Content-Length锛泂ha256 缁?HF Xet CAS 鍦板潃锛堝唴瀹瑰鍧€ sha256锛夛紱璁稿彲璇佺粡浠撳簱鏍囩
  DiT Q4_0=3,029,086,560B锛圦uantStack, Apache-2.0锛夛紱Q4_K_M=3,433,116,000B锛決8_0=5,400,179,040B锛坰ha 寰呰ˉ锛岄檺娴侊級
  VAE=1,409,400,960B锛?ComfyOrg 鍚屾枃浠讹級锛泆mt5 Q8_0=6,043,068,256B锛坈ity96, Apache-2.0锛夛紱Q4_K_M=3,655,145,312B锛坰ha 寰呰ˉ锛?  瀹樻柟鍩烘ā鍨?Wan-AI/Wan2.2-TI2V-5B=Apache-2.0锛堝凡鏍革級锛汣omfyOrg fp16锛?,999,658,848B锛夎鍙瘉鏍囩琚檺娴侊紝澶嶆牳鍓嶄笉鍏ュ彲涓嬭浇娓呭崟
  鎺掗櫎椤癸紙Turbo 钂搁/TAEHV/wan2.1_vae/A14B/LoRA/Hexagon kernel锛夊湪鏂囨。涓庝唬鐮佹敞閲婁腑鏄惧紡澹版槑
result: 浠ｇ爜+鏂囨。 PASS锛堢紪璇戠骇锛涙祴璇曠粨鏋滃苟鍏ヤ笅涓€鐐规锛夛紱"鍙瘉鏄庤祫浜у彲鏍￠獙锛屼笉璇佹槑 native 鍙繍琛?= 鍗″嚭鍙ｅ師鏂囷紝P0-C NOT_RUN
unverified: 3 涓緟琛?sha256 + ComfyOrg 璁稿彲锛堟仮澶嶆潯浠?HF 闄愭祦瑙ｉ櫎閲嶅彇锛夛紱妯″瀷涓嬭浇閾捐矾锛堟帴鍏ヤ笅杞?adapter 鏃堕獙璇侊級
rollback_point: 鍒犻櫎 video/model/ 涓夋枃浠?娴嬭瘯+app 渚濊禆琛岋紱涓嶈Е纰扮敤鎴锋棦鏈夋ā鍨?next_card: V1-03 鐨勮祫浜ц緭鍏ュ凡灏辩华锛坢anifest鈫抴orker 鏍￠獙閾惧湪 V1-02b/V1-03 鎺ョ嚎锛?```

---

## V0-03 妯℃澘涓庡彲缂栬緫鍘熺敓鑺傜偣鍥剧旱鍚戝垏鐗?
```text
card: V0-03
start_revision: 鍚屽墠
actual_write_set锛堝叏閮ㄥ湪 feature/video-workspace 涓?app/video/workflow锛岀嫭鍗犲啓闆嗭級:
  feature/.../VideoWorkflowTemplates.kt 鈥斺€?鎷熸柊澧烇細t2v/i2v 妯℃澘鏋勫缓鍣紙=鏅€氭枃妗ｏ紝鍚屼竴鏍￠獙鍚屼竴缂栬瘧鍣紱鍩虹嚎榛樿鍊硷級
  feature/.../editor/VideoGraphEditorState.kt 鈥斺€?鎷熸柊澧烇細缂栬緫鍛戒护鏍稿績锛堟瘡鍛戒护鈫掓柊涓嶅彲鍙樿崏绋?鍏变韩濂戠害鍏ㄩ噺鏍￠獙锛?    杩炵嚎鍓嶇疆鎷掔粷锛氱鍙ｇ己澶?瀛楅潰閲忓彛/绫诲瀷涓嶅尮閰?閲嶅鐩爣/鎴愮幆锛涘垹闄ゅ奖鍝嶉瑙堬紱鏈夌晫鎾ら攢閲嶅仛锛涗綅缃笉杩涘巻鍙蹭笉杩?digest锛?  feature/.../editor/VideoDraftCodec.kt 鈥斺€?鎷熸柊澧烇細鑽夌 SavedState 缂栬В鐮侊紙鍚?UI 浣嶇疆/鎶樺彔/鏍囩锛?xx 杞箟锛涚増鏈墠缂€锛?  feature/.../VideoWorkspaceViewModel.kt 鈥斺€?鎷熸柊澧烇細椤甸潰鐘舵€佹寔鏈夎€咃紙绐?port 娉ㄥ叆鍙┖=鏈帴绾匡紱鑽夌缁?SavedStateHandle 瀛樼画锛泂ubmit 杞彂 owner锛?  feature/.../ui/VideoNodePresentations.kt 鈥斺€?鎷熸柊澧烇細绫诲瀷鈫掍腑鏂囨爣绛?鎽樿/瀛楁瑙勬牸锛堢函灞曠ず鎶曞奖锛?  feature/.../ui/VideoWorkspaceScreen.kt 鈥斺€?鎷熸柊澧烇細妯″紡琛?鏂囩敓/鍥剧敓)+绠€鍖栭摼(琛?鏂囨。鐪熷疄鑺傜偣+鐪熷疄鍙傛暟+鏍￠獙寰芥爣)+棰勮(璇氬疄鏈帴绾挎枃妗?+搴曢儴涓绘搷浣?鏈帴绾挎椂鏄庣‘鎷掔粷鏂囨)+鎾ら攢閲嶅仛
  feature/.../ui/VideoNodeSheet.kt 鈥斺€?鎷熸柊澧烇細鍙傛暟 bottom sheet锛堟彁绀鸿瘝澶氳/鏁存暟/瀹氱偣灏忔暟瑙ｆ瀽锛涜妭鐐圭骇闂鍐呰仈锛涘垹闄ゅ墠褰卞搷瀵硅瘽妗嗭紱杩炵嚎鏂紑锛?  feature/.../ui/VideoGraphCanvas.kt 鈥斺€?鎷熸柊澧烇細楂樼骇鐢诲竷锛堝弻鎸囩缉鏀惧钩绉?鑺傜偣鎷栧姩鍥炲啓浣嶇疆/绔彛鐐归€夎繛绾匡級+ 鍙闂妭鐐瑰垪琛紙鎸夐挳寮忚繛绾挎浛浠ｏ紝鎷栨嫿涓嶆槸鍞竴鏂瑰紡锛?  feature/build.gradle.kts 鈥斺€?杩藉姞 lifecycle-viewmodel-compose/runtime-compose 渚濊禆锛堝榻愬叾浠?feature锛?  feature/src/test: VideoGraphEditorStateTest(12) + VideoV0SupportTest(6) 鈥斺€?鎷熸柊澧?  app/.../video/workflow/VideoWorkflowCompiler.kt 鈥斺€?鎷熸柊澧烇紙V1-02a 缂栬瘧閾炬彁鍓嶈惤鍦帮級锛氬彈闄?DAG鈫掑喕缁撳揩鐓х敓浜х紪璇戝櫒
  app/src/test/.../VideoWorkflowCompilerTest.kt 鈥斺€?鎷熸柊澧烇紙8 娴嬭瘯锛?鍏抽敭琛屼负: 妯℃澘涓庢墜宸ュ浘鍏辩敤鍚屼竴 compiler锛坈ompiler 娴嬭瘯鐩存帴娑堣垂 feature 妯℃澘锛岀粨鏋勪笂闃?瑁呴グ鎬х紪杈戝櫒"锛夛紱杩愯缁戝畾鍐荤粨蹇収锛岀紪杈戜笉褰卞搷鎵ц涓姹傦紙缂栬瘧鍣ㄤ粠涓嶅彲鍙?document 杈撳叆锛?commands_and_results:
  :feature:video-workspace:compileDebugKotlin 鈫?PASS锛堜笁娆¤凯浠ｄ慨缂栬瘧閿欒锛氱己 lifecycle 渚濊禆/鍙?remember/BoxScope/tap 绛惧悕/BorderStroke 闄愬畾锛?  :feature:video-workspace:testDebugUnitTest 鈫?18 娴嬭瘯 0 澶辫触锛堥杞?4 澶辫触鍧囦负娴嬭瘯棰勬湡閿欙細V1 绫诲瀷绯荤粺涓嬮潪鑷幆涓嶅彲鏋勬垚銆乽ndo 涓婇檺璇箟銆乮2v 鏃犲浘灞炲悎娉曟湭瀹屾垚鑽夌锛涘疄鐜版棤璇紝淇祴璇曞悗鍏ㄧ豢锛?  codec 涓や釜鐪熷疄 bug 鐢辨祴璇曟姄鍑哄苟淇锛歅/U 鏍囪缂哄垎闅旂銆乺emovePrefix 鍓嶅绌哄瓧娈点€乴abel 鏈浆涔?result: 瑙佷笅鏂圭粺涓€楠岃瘉鍧?rollback_point: 鎾ゅ洖 feature 鏂版枃浠朵笌 app/video/workflow锛涘绾︽湭鍔?```

### 鏈€缁堥獙璇佽褰曪紙V0-02/V1-00/V1-01/V0-03 + V1-02a 缂栬瘧閾惧墠缃欢锛?
鍏ㄩ儴缁撴灉鏉ヨ嚜鏈浼氳瘽瀹為檯杩愯銆佹寜 XML mtime 杩囨护鐨勬湰娆℃姤鍛婏紙鏃犳棫鏃ュ織鍐掑厖锛夛細

| 鑼冨洿 | 鍛戒护锛堝叏閮ㄧ粡 McaBuildLock 涓茶锛?| 娴嬭瘯鏁?| 澶辫触 | 缁撹 |
| --- | --- | --- | --- | --- |
| 鏋舵瀯闂ㄧ | `python tools/architecture/check_architecture_rules.py` | 鈥旓紙19 瑙勫垯锛?| 0 | PASS锛坋xit 0锛?|
| core:video-contract | `:core:video-contract:testDebugUnitTest --rerun` | 45 | 0 | PASS |
| feature:chat 鍏ㄩ噺鍥炲綊 | `:feature:chat:testDebugUnitTest --rerun` | 484锛?4 濂椾欢锛?| 0 | PASS锛堝惈鎶藉眽濂戠害 2锛?|
| feature:video-workspace | `:feature:video-workspace:testDebugUnitTest` | 18 | 0 | PASS |
| app 瑙嗛鐩稿叧鍥涘浠?| `:app:testDebugUnitTest --tests=...锛? 绫伙級--rerun` | 20 | 0 | PASS锛坋xit 0锛?|
| 缂栬瘧 | `:app:compileDebugKotlin`锛堝惈 chat/video-workspace锛?| 鈥?| 鈥?| PASS锛圔UILD SUCCESSFUL锛?|

鍚堣 567 涓?JVM 娴嬭瘯 0 澶辫触銆傛祴璇曡繃绋嬩腑鎶撳嚭骞朵慨澶嶇殑鐪熷疄缂洪櫡锛歝odec 鍒嗛殧绗?鍓嶇紑/杞箟 3 澶勩€丳rofiles 瀵硅薄鍒濆鍖栭『搴?NPE銆侀獙璇佸櫒 Ready 璇箟锛圫izeOnlyOk 涓嶅緱绠楁牎楠屽畬鎴愶級銆佺紪璇戝櫒鐗╁寲瑙ｆ瀽缁戝畾淇濈暀 sha銆佷袱澶?Kotlin 璇硶/绛惧悕閿欒鈥斺€斿潎涓烘祴璇曞厛琛屾毚闇诧紝闈炰簨鍚庤ˉ娴嬨€?
杩唬涓繕澶勭悊鐨勫閮ㄤ簨浠讹紙璁板綍锛夛細骞惰 Agent 鎸佹瀯寤洪攣涓?git index.lock锛堝娆＄瓑寰?閲嶈瘯锛夛紱`Wan22Ti2v5bProfiles.kt` 涓€搴﹁澶栭儴绉婚櫎锛堢嫭鍗犲啓闆嗘枃浠讹紝宸查噸鍐欏苟鍏ㄩ儴娴嬭瘯閫氳繃锛夛紱dsh-runtime 鏈彁浜や唬鐮佷腑鏈熺紪杈戝鑷翠袱娆?input-changed-during-run锛堟寚绾归棬绂佹纭嫆缁濆綊鍥狅級銆?
**鏈獙璇?鍙楅檺椤癸紙璇氬疄杈圭晫锛?*锛?- 鐪熸満 UI 娓叉煋/鏃犻殰纰?瑙嗚涓よ疆 QA锛歂OT_RUN锛堟棤杩炴帴璁惧锛沄0-03 鐨勮瑙夐獙鏀朵笌 V1-06 涓€璧疯ˉ锛夈€?- 璺ㄨ繘绋嬫枃浠堕攣涓庢寔鏈夎繘绋嬫浜￠噴鏀撅細NOT_RUN锛圝VM 鏃犳硶鏋勯€犵浜岃繘绋嬶紱V1-02b 鍙岃繘绋?worker 鏃堕獙璇侊級銆?- `scripts/verify-code.ps1 -VideoTests` 鍏ㄩ€氶亾绔埌绔細BLOCKED锛坈ompile lane 鍚?:app鈫掍粬浜鸿繘琛屼腑鐨?dsh-runtime锛涙仮澶嶆潯浠?璇ョ洰褰曠ǔ瀹氬悗閲嶈窇锛夈€?- Room 杩佺Щ/androidTest銆乶ative銆佸獟浣撴挱鏀俱€佺敓浜ч獙鏀讹細灞?V1-02a 鍙婂悗缁崱锛屾湭寮€濮嬪鏈櫄鎶ャ€?
**涓嬩竴鍗?*锛歏1-02a 鍓╀綑閮ㄥ垎锛圧oom video 琛?杩佺Щ v29鈫?0銆乯ournal銆佽繘绋嬬骇 owner 涓?VideoHostAssembly 瑁呴厤銆佸叧闂涔夛級锛岄殢鍚?V1-02b worker銆俈1-02a 鐨勭紪璇戦摼鍓嶇疆浠讹紙VideoWorkflowCompiler+娴嬭瘯锛夊凡鍦ㄦ湰杞惤鍦板苟閫氳繃銆?
---

## V1-02a 鎸佷箙 run銆佽繘绋嬬骇 owner 涓庤閰嶏紙涓讳綋瀹屾垚锛?
```text
card: V1-02a
start_revision: 鍚屽墠
actual_write_set:
  core/video-contract/.../VideoWorkflowPort.kt 鈥斺€?port 澧炵 6 椤硅兘鍔?saveRevision锛堣崏绋垮喕缁撲负涓嶅彲鍙樹慨璁紱骞傜瓑锛涘悓 id 寮傚唴瀹?Conflict锛?  app/.../video/data/VideoDatabaseSchema.kt 鈥斺€?鎷熸柊澧烇細video_workflow_revisions/video_runs/video_run_events 涓夎〃瀹炰綋
    + VideoWorkflowDao锛堟潯浠惰浆绉?UPDATE runId+attemptId+stateVersion+fromState锛岃繑鍥炶鏁帮級+ MIGRATION_29_30锛堢函澧為噺 DDL锛?  app/.../video/data/VideoRunStore.kt 鈥斺€?鎷熸柊澧烇細Store 鎺ュ彛锛圝VM 鍙祴锛? RoomVideoRunStore 瀹炵幇
    + VideoGraphJsonCodec/VideoSnapshotCodec锛坥rg.json锛屽畾鐐瑰皬鏁颁繚鎸?unscaled:scale锛?  app/.../video/execution/VideoGenerationOwner.kt 鈥斺€?鎷熸柊澧烇細杩涚▼绾?owner锛堝敮涓€鐘舵€佸喅绛栬€咃級锛?    submit=缂栬瘧鈫掑喕缁?闅忔満绉嶅瓙 SecureRandom)鈫扱UEUED 鏉′欢鍐欏叆鈫抣aunch attempt锛沘dapter 鍙┖=璇氬疄鎷掔粷 BACKEND_UNAVAILABLE 涓旈浂鎸佷箙鍖栵紱
    鍙栨秷涓夊眰锛歈UEUED 鐩磋揪 CANCELLED(鏃犺祫婧?/杩愯涓?CANCEL_REQUESTED鈫抋dapter 纭 Stopped鈫扖ANCELLED+RELEASED锛?    鏅氬埌鎴愬姛鍦?CANCEL_REQUESTED 鍚庣粨绠椾负 CANCELLED锛涢樁娈靛洖璋?suspend 淇濆簭椹卞姩涓氬姟鎬佺嚎鎬ф帹杩涳紱
    鍚姩瀵硅处锛氶潪缁堟€佽鈫扞NTERRUPTED+UNCONFIRMED锛況etry=鏂?run/attempt 寮曠敤鍘熶慨璁紱stale attempt 鍐欏叆琚?attempt 瀹堝崼涓㈠純
  app/.../composition/VideoHostAssembly.kt 鈥斺€?鎷熸柊澧烇細姣忚繘绋嬪敮涓€ owner 瑁呴厤锛圓tomicBoolean 闃蹭簩娆℃瀯閫狅紱executor=null 鐩村埌 V1-02b锛?  app/.../composition/MainRuntimeAssembly.kt 鈥斺€?娉ㄥ唽 videoHost锛坉shHost 鍚屾ā寮忥級
  app/.../MainViewModel.kt 鈥斺€?绐勮闂櫒 videoWorkflowPort
  app/.../MainActivity.kt 鈥斺€?VideoWorkspaceRoute(port = viewModel.videoWorkflowPort)锛堟浛鎹?V0-02 鐨勬樉寮?null锛?  app/.../ChatSessionStoreRoomDatabase.kt 鈥斺€?娉ㄥ唽涓夊疄浣?DAO/MIGRATION_29_30锛寁ersion 29鈫?0锛堝疄浣撹拷鍔犲湪鍒楄〃灏撅紝鏃ц〃闆舵敼鍔級
  feature/.../VideoWorkspaceViewModel.kt 鈥斺€?submit 鍏?saveRevision 鍐荤粨鑽夌鍐嶆彁浜?  app/src/test/.../VideoGenerationOwnerTest.kt 鈥斺€?鎷熸柊澧烇細FakeStore锛堝惈鐗堟湰鏉′欢杞Щ璇箟锛? 鍙帶 FakeAdapter锛?1 娴嬭瘯
production_callers: MainActivity 鈫?viewModel.videoWorkflowPort 鈫?VideoHostAssembly(鍗曚緥 owner) 鈫?RoomVideoRunStore(mca.db v30)锛?  鐘舵€佸敮涓€鍐欏叆鑰?owner锛堢粡 DAO 鏉′欢浜嬪姟锛夛紱椤甸潰/鏈潵 API/worker 鍙粡 port
commands_and_results锛堟湰鍗℃渶缁堜竴娆★紝鍏ㄩ儴 --rerun锛孹ML 涓烘湰娆¤繍琛岋級:
  :app:testDebugUnitTest --tests={OwnerTest,CompilerTest,VerifierTest,AppTabTest,GateTest} 鈫?exit 0
  娴嬭瘯 31 / 澶辫触 0锛圤wnerTest 11 + Compiler 8 + Verifier 7 + AppTab 2 + Gate 3锛?  锛堣繃绋嬶細8 杞凯浠ｄ慨澶嶁€斺€斾袱澶勭己 import銆乵ap 鎵╁睍璋冪敤銆乿alue-class 浼犲弬銆乺ow 蹇収瀛楁銆?   闃舵鍥炶皟淇濆簭鍗忚閲嶆瀯锛堝紓姝?launch鈫抯uspend 鐩磋皟锛夈€佸嚱鏁扮被鍨嬮粯璁ゅ弬銆佺嚎鎬ц浆鎹㈤摼 fake 鑴氭湰鈥斺€?   姣忚疆鍧囩敱鐪熷疄娴嬭瘯澶辫触椹卞姩锛岄潪浜嬪悗琛ユ祴锛?result:
  JVM 鐘舵€佹満+瑁呴厤: PASS锛?1/31锛涚紪璇戝惈 :app 鍏ㄨ閰嶄笌 feature锛?  Room 杩佺Щ 29鈫?0 鐪熸満/androidTest: NOT_RUN锛圡igrationTestHelper 闇€璁惧锛?0.json schema 闅忔瀯寤哄鍑猴紝璁惧鍒颁綅鍚庢寜 AgentRoomMigrationTest 妯″紡琛ワ級
  鏂囦欢 journal锛埪?.2 宕╂簝绐楀彛琛級: 鎺ㄨ繜鑷?V1-04 涓庡獟浣撴枃浠跺彂甯冧竴骞跺疄鐜扳€斺€攔un 鐘舵€佹仮澶嶇敱 Room 琛屾潈濞佹壙鎷咃紙QUEUED/PREPARING鈫掗噸鍚啋INTERRUPTED 宸叉祴锛夛紝
    鏂囦欢+DB 涓ら樁娈靛師瀛愭€у湪鏃犳枃浠朵骇鐗╃殑鏈崱鏃犲璞★紱璁板綍涓鸿璁″喅瀹氳€岄潪閬楁紡
  杩涚▼鏍瑰叧闂涔夛紙pending-close/closing barrier锛? 閮ㄥ垎鈥斺€攐wner 宸叉寕 process scope 绂婚〉涓嶅彇娑堬紱McaProcessRuntimeOwner 鐨勬渶鍚?Activity
    鍏抽棴 barrier 涓庤棰?task hold 鎺ョ嚎闅?V1-02b worker 涓€骞跺鐞嗭紙褰撳墠鏃犻暱椹昏祫婧愶紝owner 鏃犳墽琛屽櫒鏃朵笉鎸佽祫婧愶級
rollback_point: 鎾ゅ洖鏈崱 11 涓枃浠?diff + DB version 鍥?29锛堣縼绉讳负绾閲忥紝鍥為€€鏃?APK 涓嶇牬鍧忔棫琛ㄨ鍙栵紱鏂拌〃娈嬬暀鏃犲锛?next_card: V1-02b 鐙珛 :local_video worker + AIDL + FGS锛圥0-B锛夛紱闅忓悗 V1-03 鐪熷疄 JNI/native
```

### 浼氳瘽绱楠岃瘉锛堝叏閮ㄦ湰娆¤繍琛?XML锛?
| 鑼冨洿 | 娴嬭瘯 | 澶辫触 |
| --- | --- | --- |
| core:video-contract | 45 | 0 |
| feature:chat 鍏ㄩ噺鍥炲綊 | 484 | 0 |
| feature:video-workspace | 18 | 0 |
| app 瑙嗛浜斿浠讹紙owner/compiler/verifier/tab/gate锛?| 31 | 0 |
| **鍚堣** | **578** | **0** |

鏋舵瀯闂ㄧ 19 瑙勫垯 PASS銆?
---

## V1-02b 鐙珛 worker銆佸墠鍙版墽琛屼笌 IPC锛圥0-B 涓讳綋锛?
```text
card: V1-02b
start_revision: 鍚屽墠
actual_write_set:
  app/src/main/aidl/com/muyuchat/mca/ILocalVideoWorker.aidl 鈥斺€?鎷熸柊澧炵獎鍗忚锛歜egin(浜旀樉寮忓瓧娈?callback,
    杩斿洖 Boolean 鍗?active 鎷掔粷)/cancel/cancelAndExit/shutdown锛涘揩鐓х粡 codec JSON 瀛楃涓茶繃 Binder锛堜粎鍏冩暟鎹紝
    绂佹甯?MP4 瀛楄妭锛?  app/src/main/aidl/com/muyuchat/mca/ILocalVideoWorkerCallback.aidl 鈥斺€?鎷熸柊澧烇細onStage(hasStep=false 琛ㄧず鏃犲疄娴?    杩涘害锛屼笉浼€犵櫨鍒嗘瘮)/onOutcome(succeeded|failed|stopped)
  app/.../video/worker/VideoWorkerCore.kt 鈥斺€?鎷熸柊澧炵函 JVM 鏍稿績锛氬崟 active+鏄惧紡閲嶅鎷掔粷锛涜韩浠芥牎楠屽彇娑堬紱
    瀹㈡埛绔浜♀啋鍙栨秷锛涘崗浣滃仠姝㈡爣蹇楋紱settle-exactly-once锛沞xecutor 鏃犵粨绠椻啋鍝嶄寒澶辫触锛泈atchdog 寮洪€€娉ㄥ叆鐐癸紱
    UNAVAILABLE_EXECUTOR=璇氬疄 BACKEND_UNAVAILABLE锛堥浂闃舵闆跺抚锛岀粷涓嶅啋鍏呯敓鎴愶級
  app/.../video/worker/LocalVideoWorkerService.kt 鈥斺€?鎷熸柊澧?:local_video 杩涚▼鏈嶅姟澹筹細binder death鈫抍ore.onClientDied锛?    specialUse FGS(瀛愮被鍨?local_video_generation)+閫氱煡娓犻亾+绌洪棽鑷仠锛汵ativeMediaExecutionGate 鍏变韩閿佸煙鎺ュ叆鐐?    锛坅cquireGate 娉ㄥ叆锛夛紱cancelAndExit=鏉€鏈竴娆℃€ц繘绋嬶紙owner 渚ф寜姝讳骸瀵硅处锛?  app/src/main/AndroidManifest.xml 鈥斺€?娉ㄥ唽 :local_video 鏈嶅姟锛坋xported=false, stopWithTask=false, 杩藉姞涓嶆敼鍔ㄦ棦鏈夊０鏄庯級
  app/src/test/.../VideoWorkerCoreTest.kt 鈥斺€?鎷熸柊澧?8 鍗忚娴嬭瘯
production_callers: Owner鈫?V1-03 鎺?worker adapter 缁戝畾)鈫扞LocalVideoWorker.begin鈫抔ate鈫抏xecutor锛?  worker 涓嶅啓 Room 缁堟€侊紱姝讳骸鐢?owner 瀵硅处涓?INTERRUPTED锛坥wner 渚у凡鏈夋祴璇曡鐩栬缁撶畻锛?commands_and_results:
  :app:testDebugUnitTest --tests=VideoWorkerCoreTest --rerun 鈫?BUILD SUCCESSFUL in 5m47s锛坋xit 0锛?  璇佹嵁閲嶈窇锛堥攣绛夊緟瀵规柟 deviceTest 鍚庯級: exit 0锛孹ML 璁℃暟 VideoWorkerCoreTest=8/0 澶辫触 + VideoGenerationOwnerTest=11/0 澶辫触
  杩唬淇锛歽ield() 杞鍦ㄨ櫄鎷熸椂闂翠笅鍚屽埢鑷棆鎸傝捣鈫抎elay(1)锛堟祴璇曡嚜韬己闄凤紝鏍稿績鏃犺锛?result:
  鍗忚鏍稿績+鏈嶅姟+娓呭崟: PASS锛堢紪璇?娴嬭瘯杩愯鎴愬姛锛涢€愮被璁℃暟瑙佷笅鏂硅ˉ璇侊級
  owner鈫攚orker 璺ㄨ繘绋嬬粦瀹?鐪熷疄 binder death/FGS 鍚姩鎷掔粷/閫氱煡鍙栨秷: NOT_RUN锛堥渶鐪熸満锛?    鎭㈠鏉′欢=璁惧杩炴帴鍚?instrumented 楠岃瘉锛岄殢 V1-03 璁惧鍏冲彛涓€骞惰窇锛?  鐪熷疄 native 鎵ц: NOT_RUN锛坋xecutor 璇氬疄涓嶅彲鐢紝灞?V1-03锛?rollback_point: 鍒犻櫎 2 涓?AIDL+worker 涓ゆ枃浠?娓呭崟娈?娴嬭瘯锛涙棤 Room/濂戠害鍙樻洿闇€瑕佸洖閫€
next_card: V1-03 鐪熷疄 JNI/native锛圥0-C锛夆€斺€攂ridge generate_video 鏄犲皠+worker adapter 鎺ョ嚎瑁呴厤
```


---

## V1-03 鐪熷疄 JNI/native銆佸弬鏁板洖鏄句笌鍙栨秷杈圭晫锛圥0-C 涓讳綋锛?
```text
card: V1-03
start_revision: 68b6eef1e67870b02bfef6afda2e625d36663da9锛堟湭鎻愪氦锛?actual_write_set:
  core/sd-native/src/main/cpp/sd_video_generate.cpp 鈥斺€?鎷熸柊澧烇細fused Wan2.2 TI2V-5B generate_video JNI 瀹炵幇锛?    璇锋眰 JSON 鏍￠獙锛堝嚑浣?32 鍊嶃€佸抚 4k+1銆乪uler銆乬uidance/flow_shift 鑼冨洿锛夈€佹ā鍨?杈撳叆 regular-file 涓?    staging root 鍖呭惈鏍￠獙銆佺鐩樹綑閲忎笌 spool 涓婇檺銆丳PM P6 閫愬抚鍐欏嚭骞剁珛鍗抽噴鏀炬瘡甯с€佸け璐?鍙栨秷娓呯悊銆?    sd_ctx/甯ф暟缁?杈撳叆鍥?RAII銆丏SO 鍏ㄥ眬 cancel/progress 鍥炶皟涓茶瀹夎骞跺湪 finally 娓呴櫎
  core/sd-native/src/main/cpp/CMakeLists.txt 鈥斺€?杩藉姞 sd_video_generate.cpp
  core/sd-native/.../NativeStableDiffusionBridge.kt 鈥斺€?杩藉姞 generateVideo/getVideoProgress/cancelVideo
  app/.../video/worker/VideoWorkerPipeline.kt 鈥斺€?鎷熸柊澧烇細manifest 瑙掕壊/灏哄/鏍￠獙鍜岄獙璇?鈫?PREPARE/LOAD/
    TEXT_ENCODE 鈫?鐪熷疄 sampler 杩涘害杞 鈫?native 鏈夋晥鍙傛暟閫愬瓧娈靛洖鏄炬牎楠?鈫?DECODE 瀹屾垚鏍囪 鈫?    MediaCodec 缂栫爜 鈫?Succeeded(output.mp4)锛涘彇娑堝悗绛夊緟 native 杩斿洖鎵嶇粨绠?Stopped
  app/.../video/worker/LocalVideoWorkerAdapter.kt 鈥斺€?鎷熸柊澧烇細chat lifecycle lease 鈫?worker 缁戝畾 鈫?    executionJson 鈫?鍗?active/绛夊緟鍙栨秷 鈫?stage/outcome 鏄犲皠锛沚inder death=INTERRUPTED锛涚湅闂ㄧ嫍
  app/.../video/worker/VideoWorkerCore.kt / LocalVideoWorkerService.kt / AIDL 鈥斺€?executionJson 瀛楁锛?    service 鎵ц浣撴崲涓?VideoWorkerPipeline锛堜笉鍐?UNAVAILABLE_EXECUTOR锛?  app/.../video/execution/VideoGenerationOwner.kt 鈥斺€?request 鎼哄甫 document/remote binding锛涘惎鍔ㄥ璐﹀彧澶勭悊
    鏋勯€犳椂蹇収锛汼ucceeded 璧?publisher + 鏉′欢 COMPLETED 浜嬪姟锛汭nterrupted锛涙湭璇锋眰鐨?Stopped=FAILED锛?    retry seedOverride锛況emoteExecutor
  contract 鎵╁睍锛歏ideoMediaContract/VideoImportContract/VideoRemoteContract锛沄ideoWorkflowPort.media library銆?    submit remoteBinding/seedOverride銆乸rojection remote id
  app/.../composition/VideoHostAssembly.kt + MainViewModel/MainActivity 鈥斺€?publisher/worker/import/remote
    鍞竴瑁呴厤锛汳ainViewModel 瀹夎鑱婂ぉ engine 鐢熷懡鍛ㄦ湡绉熺害
verification:
  :core:sd-native:assembleDebug --rerun-tasks 鈫?BUILD SUCCESSFUL锛坅rm64-v8a + x86_64 鍧囩紪璇?閾炬帴锛?    鏃ュ織 %TEMP%\mca-sdnative-refresh2.log锛夛紱棣栬疆鐪熷疄鎶撳埌 C++ `frames` 鍙橀噺閬斀缂栬瘧閿欒骞朵慨澶?  :app:compileDebugKotlin 鈫?BUILD SUCCESSFUL锛坴8锛涘惈 AIDL/Room/feature/remote锛?  :app:testDebugUnitTest --tests 'com.muyuchat.mca.video.*' 鈫?130 tests / 0 failed / 0 skipped
    锛坥wner 13銆亀orker 8銆乧ompiler 8銆乿erifier 7銆乧odec 5銆乮mporter 50銆乺emote 39锛涙湰娆?XML mtime fresh锛?  instrumentation锛?026-10-26 03:08锛?5091RP04C锛夛細9 tests / 3 pass / 6 fail锛堥杞疄鐜版毚闇茬湡瀹為棶棰橈級
    PASS: native 缂哄け缁勪欢鈫扢ODEL_MISSING銆?9鈫?2 鍏ㄨ〃鍒涘缓銆佹崯鍧忓抚鈫扚ailed锛堜笉浜у嚭鍗婃垚鍝侊級
    FAIL: 64x64 缂栫爜 CodecException銆佸彇娑堝洖璋冭鏁版柇瑷€銆乸ublisher 鍥犵紪鐮佸け璐ャ€?0鈫?2 鏂█鍐欏弽銆?          native 鏃犳晥璇锋眰鍚庤繘搴︽柇瑷€銆丆ompose/Espresso 鍦?Android 16 鐨?InputManager 鍏煎澶辫触
  淇鍚庨噸瑁咃細BLOCKED锛堜袱鍙拌澶?MIUI INSTALL_FAILED_USER_RESTRICTED锛涢渶璁惧绔€淯SB 瀹夎/纭鈥濇巿鏉冿級

result:
  JNI/鍙傛暟/缂栬瘧/鐘舵€佹満/缂栫爜绾€昏緫: PASS锛堣寖鍥村涓婏級
  鐪熷疄妯″瀷 load/sample/decode: BLOCKED鈥斺€斾富鏈轰笌璁惧鍧囨棤 Wan2.2 TI2V-5B 鏉冮噸锛涙仮澶嶆潯浠?鎸?    MODEL_ASSET_SOURCES 鏀剧疆 filesDir/video_models/wan2.2-ti2v-5b/{dit,text_encoder,vae} 涓旀牎楠岄€氳繃
  鐪熷疄 native 鍐呭瓨宄板€?鍙栨秷鏃剁偣/閲嶅杩愯: NOT_RUN锛堜緷璧栨潈閲嶄笌璁惧 P0-C锛?unverified_or_blocked:
  - 鍏ㄩ噺 sha256 鏍￠獙鍦ㄦ瘡娆′换鍔″墠娴佸紡鎵ц锛?2GB 绾у埆鐨勮€楁椂鏈疄娴嬶紱濡傝秴鏃跺睘浜?P0 棰勭畻闂
  - decode 闃舵鏃犵粏绮掑害鍥炶皟锛孌ECODE 鍙湪 native 杩斿洖鍚庝綔涓哄畬鎴愭爣璁颁笂鎶ワ紱涓嶅彲涓柇闃舵鐢?worker watchdog 鏀跺彛
  - 鏈绉板父閲忓唴瀛橈細sd.cpp 鍏堟寔鏈夋暣娈?float tensor 鍐嶅垎閰嶅叏閮?RGB 甯?rollback: 鍒犻櫎 sd_video_generate.cpp/CMake 琛?bridge 鏂规硶/worker pipeline 涓?adapter锛屾仮澶?executor=null锛?  涓嶆敼鍥剧墖 bridge 鏃㈡湁璇箟
next_card: V1-04 缂栫爜/鍙戝竷锛堝凡瀹炵幇锛岃涓嬶級
```

## V1-04 缂栫爜銆佹牎楠屻€佷袱闃舵鍙戝竷涓庢仮澶?
```text
card: V1-04
actual_write_set:
  app/.../video/codec/VideoMp4Encoder.kt 鈥斺€?MediaCodec AVC + MediaMuxer锛汻GB鈫扞420锛圔T.601 limited锛夛紝
    PTS=frameIndex*1e6/fps锛孍OS drain 涓?60s 鍋滄粸 watchdog锛屽彇娑?澶辫触娓呯悊 part
  app/.../video/media/VideoMp4Verifier.kt 鈥斺€?MediaExtractor 杞ㄩ亾/H.264/灏哄/閲囨牱鏁?鏃堕暱 + 棣栦腑鏈抚瑙ｇ爜 smoke锛?    鏈夐煶杞ㄥ嵆澶辫触锛圴1 鏃犻煶杞級
  app/.../video/media/VideoMediaPublisher.kt 鈥斺€?verify鈫抯ha256鈫抎urable journal鈫抐sync/atomic rename鈫?    owner 鏉′欢 COMPLETED 浜嬪姟鈫抝ournal/temp 娓呯悊锛涘穿婧冪獥鍙?reconcile锛坴erified/renamed/committed锛?    宸叉彁浜ゆ垚鍔熶笉闅旂锛屾湭纭瀛楄妭鍏?quarantine锛夛紱鍒犻櫎鍏?tombstone 鍐?unlink 鑷湁鏂囦欢
  app/.../video/data/VideoDatabaseSchema.kt 鈥斺€?video_media_assets + 鏉′欢 completeRunWithMedia 浜嬪姟 +
    MIGRATION_30_31 / MIGRATION_31_32锛坮emote id锛?  ChatSessionStoreRoomDatabase.kt 鈥斺€?version 32銆佸疄浣?杩佺Щ娉ㄥ唽锛汧ileProvider xml 澧炲姞 video/media
verification:
  JVM VideoMp4EncoderTest 5/5 PASS锛圥PM 涓ユ牸璇诲彇銆佸昂瀵?灏鹃殢鎷掔粷銆侀粦鐧?YUV銆佽壊搴︺€両420 澶у皬锛?  app 缂栬瘧 PASS锛汻oom 30鈫?2 / 29鈫?2 杩佺Щ涓庣湡瀹炵紪鐮?鍙戝竷/鍒犻櫎瑙?instrumentation 缁撴灉锛堜笅锛?  instrumentation锛堥杞級: 缂栫爜 1/3 PASS锛堝潖甯ф嫆缁濓級锛岀湡瀹炵紪鐮?鍙戝竷/鍒犻櫎 FAIL锛?4x64 CodecException锛夛紱
    宸叉敼涓?capability 閫夋嫨 + 128x128 + 鏄庣粏閿欒锛屼絾淇鍚庨噸瑁呰 MIUI 闃绘柇锛孨OT_RUN锛堟仮澶嶆潯浠惰 搂璁惧闃诲锛?result: 绾€昏緫 PASS锛涜澶囩紪鐮?鍙戝竷/杩佺Щ缁撴灉瑙佷笅锛涙ā鍨嬭緭鍑?MP4 浠嶄緷璧?V1-03 BLOCKED
rollback: 鍒犻櫎 codec/media 鏂版枃浠朵笌 media 琛紱DB version 鍥?31锛堝獟浣撹〃绾閲忥級锛涘凡鍙戝竷鏂囦欢閲囩敤鍓嶅悜淇锛?  涓嶅垹闄ゅ叾浠?run 杈撳嚭
```

## V1-05 鎾斁鍣ㄣ€佷綔鍝佸簱銆佸垎浜€佸彟瀛樹笌鍒犻櫎

```text
card: V1-05
actual_write_set锛坒eature/video-workspace锛?
  VideoWorkspaceViewModel 鈥斺€?鐪熷疄璁㈤槄 observeRuns/observeMedia锛沘ctiveRun锛涘垹闄ょ敱绔彛缁撴灉椹卞姩锛?    Comfy 瀵煎叆 preview/save锛涘浘鐢熻棰戞湰鍦伴€夊浘锛坈ontent URI + persistable grant + 鎻愪氦鏃剁粦瀹氾級
  VideoWorkspaceRoute/Screen 鈥斺€?浣滃搧鍏ュ彛銆佷綔鍝佸簱 overlay銆佹挱鏀惧櫒 Dialog銆佸鍏?Dialog銆佽繍琛岀姸鎬佸尯銆?    杩滅 ComfyUI 鏄惧紡 endpoint/鎺㈡椿/鎻愪氦鍖?  VideoPlayerDialog 鈥斺€?骞冲彴 VideoView锛屾挱鏀?鏆傚仠/seek銆侀敊璇噸璇曘€丱N_STOP/dispose pause+stopPlayback
  VideoLibraryOverlay/Components 鈥斺€?缂╃暐鍥撅紙MediaMetadataRetriever锛夈€佺┖/閿欒/閲嶈瘯銆佺湡瀹?metadata
  VideoMediaActions 鈥斺€?content URI 鍒嗕韩锛團LAG_GRANT_READ锛夈€丼AF 鍙﹀瓨 64KiB 娴佸紡澶嶅埗銆佸け璐ュ彧娓呯悊鏈鐩爣
verification:
  :feature:video-workspace:compileDebugKotlin + testDebugUnitTest 鈫?28 tests / 0 failed锛堝惈鏂板 10锛?  VideoWorkspaceDeviceTest锛堢敓浜?MainActivity 鎶藉眽鈫掕棰戔啋鍥剧敓瑙嗛閫夊浘鍏ュ彛鈫掓埅鍥锯啋杩斿洖锛?
    棣栬疆 FAIL锛圗spresso InputManager.getInstance 鍦?Android 16 涓嶅瓨鍦級锛屽凡鏀逛负 UiAutomator锛?    淇鍚庨噸瑁?BLOCKED锛圡IUI INSTALL_FAILED_USER_RESTRICTED锛夛紝NOT_RUN
result: JVM/缂栬瘧 PASS锛涚湡鏈烘覆鏌撱€佹挱鏀?鍒嗕韩/鍙﹀瓨/鍒犻櫎 NOT_RUN/BLOCKED锛堣澶囧畨瑁呮巿鏉冿級
deviation: 鏈柊澧?Media3 渚濊禆锛屼娇鐢ㄥ钩鍙?VideoView锛涚绾挎瀯寤轰笌鍥哄畾骞冲彴 API 鍙璁★紱鎾斁鍣ㄤ笉鎸佹湁鐢熸垚浠诲姟
rollback: 鎾ゅ洖 feature 鏂版枃浠朵笌 Route 鍙傛暟锛屼笉褰卞搷 owner/store/宸插彂甯冨獟浣?```

## V2-01/V2-02 ComfyUI API-format 瀵煎叆涓?lowering

```text
card: V2-01/V2-02
actual_write_set:
  app/.../video/workflow/importer/{ComfyApiJson,ComfyApiWorkflowParser,ComfyWorkflowLowerer,
    ComfyCompatibilityMatrix}.kt + 5 娴嬭瘯鏂囦欢
  app/.../video/workflow/VideoImportAdapter.kt 鈥斺€?VideoImportPort锛?MiB 涓婇檺銆乴ower銆佷繚瀛樹笉鍙彉 revision锛?    raw API JSON 瀛?rawSourceRef锛堜笉杩?executable digest锛?verification: 50 tests / 0 failed锛圝SON 涓ユ牸鎬?11銆乸arser 16銆乴owerer 21銆乵atrix 2锛夛紱app 缂栬瘧 PASS
result: 鍥哄畾 12 绫绘槧灏?PASS锛涗粎 API format锛沀I format 鏄庣‘鎷掔粷锛涙湭鐭?鏈敤鑺傜偣涓嶉潤榛樹涪寮冿紱
  runnable 闇€ euler+normal+denoise1.0銆?k+1銆?2 鍊嶃€佷笁瑙掕壊鍚屽悕 profile銆佹棤鍥捐緭鍏ャ€乵p4/h264
unverified: 鏈鐪熷疄 ComfyUI server revision 瀵煎嚭 fixture锛堟棤鏈嶅姟鍣級锛涘吋瀹规€у０鏄庝粎瑕嗙洊鍥哄畾 fixture 涓庣煩闃?rollback: 鍒犻櫎 importer + adapter锛涙棦鏈?revision 涓嶆敼鍐?```

## V3-01 杩滅▼ ComfyUI 瀹㈡埛绔?
```text
card: V3-01
actual_write_set:
  app/.../video/remote/{RemoteComfyExecutionAdapter,RemoteComfyWaiter,RemoteComfyHttp,
    RemoteComfyWebSocket,RemoteComfyWorkflow,RemoteComfyOutput,RemoteComfyExceptions}.kt + 5 娴嬭瘯鏂囦欢
  owner/assembly/feature锛氭樉寮?REMOTE_COMFY backend + binding锛沞ndpointId/remotePromptId 鎸佷箙鍖栵紱
    probe/鎻愪氦 UI锛涘嚟鎹粎鍐呭瓨涓?Authorization header
verification: 39 tests / 0 failed锛沘pp 缂栬瘧 PASS
result: 鎻愪氦/WS 闃舵/queue-history 瀵硅处/涓嬭浇 MP4 ftyp 鏍￠獙/鎺掗槦鍒犻櫎/浠呮樉寮?targeted interrupt 鐨勪唬鐮佽矾寰?PASS锛?  鐪熷疄杩滅 server NOT_RUN锛堟棤 endpoint锛夛紱鏃犺嚜鍔ㄦ湰鍦扳啋杩滅鍥為€€锛涗涪鍝嶅簲淇濇寔 REMOTE_UNKNOWN 涓嶉噸鍙?unverified: server revision/capability 鏈疄娴嬶紱涓嶆敮鎸佽繙绔浘鐗囦笂浼狅紙鍚?LoadImage 鐨勫浘鏄惧紡鎷掔粷锛夛紱鏃犻煶棰?rollback: 鍒犻櫎 remote 鍖呬笌 UI 鍖哄潡锛屾仮澶?remoteExecutor=null锛涘凡鎸佷箙鍖?endpointId 鏃犲
```


## V1-06 褰撳墠鍊欓€夋暣鍚堥獙鏀讹紙鏈畬鎴愶級

```text
scope: 褰撳墠宸ヤ綔鍖猴紙HEAD 68b6eef + 鍏ㄩ儴鏈彁浜ゆ敼鍔級锛涘€欓€?APK 鏈敓鎴?鏈鍚嶉獙鏀?start_revision: 68b6eef1e67870b02bfef6afda2e625d36663da9锛堝垎鏀?codex/enterprise-refactor锛?existing_workspace_changes: 647+ porcelain锛堝叡浜伐浣滃尯锛屽惈浠栦汉鏀瑰姩锛夛紱鏈换鍔″啓闆嗚涓?changed_files: 瑙?V0/V1/V2/V3 鍚勫崱涓庢枃浠舵€荤洰褰?owner_and_state_writer: VideoGenerationOwner锛堝敮涓€涓氬姟鐘舵€佸啓鍏ヨ€咃級鈫?RoomVideoRunStore 鏉′欢浜嬪姟锛?  worker/remote adapter 鍙墽琛岋紱publisher 鍙仛鏂囦欢鍗忚锛涢〉闈㈠彧璇?projection
commands:
  python tools/architecture/check_architecture_rules.py 鈫?19/19 PASS (exit 0)
  :app:compileDebugKotlin 鈫?PASS
  :app:testDebugUnitTest --tests 'com.muyuchat.mca.video.*' 鈫?130/130 PASS锛堟湰娆?XML锛?  :feature:video-workspace:testDebugUnitTest 鈫?29/29 PASS锛堟湰娆?XML锛?  :core:sd-native:assembleDebug --rerun-tasks 鈫?BUILD SUCCESSFUL锛坅rm64-v8a + x86_64锛?  :feature:chat:testDebugUnitTest --rerun 鈫?486/486 PASS锛?4 濂椾欢锛屾湰娆?XML锛?  instrumentation锛堥杞級鈫?9 tests / 3 pass / 6 fail锛涗慨澶嶅悗閲嶈 BLOCKED
result: 浠ｇ爜/濂戠害/JVM/鏋舵瀯/缂栬瘧 = PASS锛堣寖鍥村涓婏級锛涚湡瀹?native 鐢熸垚銆佹ā鍨?MP4銆佽澶囨挱鏀?鍒嗕韩/鍒犻櫎銆?  璧勬簮宄板€间笌浜у搧棰勭畻 = BLOCKED/NOT_RUN
runtime_device_and_model: 25091RP04C (Android 16) 涓?2304FPN6DC (Android 16)锛涙棤 Wan2.2 鏉冮噸
raw_log_or_artifact: %TEMP%\mca-video-app-v10.log銆?TEMP%\mca-video-device3.log銆?  %TEMP%\mca-video-device5.log銆乤pp/build/test-results/testDebugUnitTest/*.xml銆?  feature/video-workspace/build/test-results/testDebugUnitTest/*.xml銆乵ca-sdnative-refresh2.log
known_limits:
  - 妯″瀷缂哄け锛氱湡瀹?load/condition/sample/decode 涓?P0-C/P0-D 鍏ㄩ儴 BLOCKED锛涗笉寰楀绉?V1 浜у搧鍙敤
  - 璁惧瀹夎琚?MIUI 鎷掔粷锛氭墍鏈変慨澶嶅悗鐨?instrumentation NOT_RUN锛堥杞?FAIL 璇佹嵁淇濈暀锛?  - 鐪熷疄杩滅 ComfyUI 鏃?endpoint锛歏3-01 浠ｇ爜/鍗曟祴 PASS锛岀湡瀹炲崗璁?NOT_RUN
  - V1 鏈紩鍏?Media3锛屼娇鐢ㄥ钩鍙?VideoView锛堝亸宸凡璁板綍锛夛紱鏃犻煶杞?  - 12GB 绾?sha256 姣忔鎻愪氦鍓嶆祦寮忔牎楠岀殑鑰楁椂鏈疄娴?rollback_point: 鍚勫崱鐙珛鍥為€€瑙佷笂锛涗笉鍒犻櫎鐢ㄦ埛鏁版嵁/宸插彂甯冨叾浠?run 浜х墿
next: 璁惧绔惎鐢?USB 瀹夎/纭鍚庨噸璺?instrumentation锛涗笅杞藉苟鏍￠獙鍩虹 TI2V-5B 鏉冮噸鍚庢墽琛?P0-C锛?  鍐嶇敱 V1-06 鍐荤粨浜у搧棰勭畻锛沄3 闇€鍥哄畾 server revision 鐨?endpoint 鎵嶈兘鍋氱湡瀹炶繙绔獙鏀?```

### 鏈€缁?JVM/闂ㄧ姹囨€伙紙鏈疆瀹為檯杩愯锛?
| 鑼冨洿 | 鍛戒护 | 娴嬭瘯/瑙勫垯 | 澶辫触 | 缁撹 |
| --- | --- | --- | --- | --- |
| 濂戠害 | `:core:video-contract:testDebugUnitTest --rerun` | 45 | 0 | PASS |
| app 瑙嗛 | `:app:testDebugUnitTest --tests 'com.muyuchat.mca.video.*' --rerun` | 130 | 0 | PASS |
| feature 瑙嗛 | `:feature:video-workspace:testDebugUnitTest --rerun` | 29 | 0 | PASS |
| chat 鍥炲綊 | `:feature:chat:testDebugUnitTest --rerun` | 486 | 0 | PASS |
| 鏋舵瀯 | `python tools/architecture/check_architecture_rules.py` | 19 瑙勫垯 | 0 | PASS |
| 缂栬瘧 | `:app:compileDebugKotlin`锛堝惈 AIDL/Room/feature锛?| 鈥?| 鈥?| PASS |
| native | `:core:sd-native:assembleDebug --rerun-tasks` | arm64-v8a + x86_64 | 鈥?| PASS锛堜粎缂栬瘧/閾炬帴锛?|
| 鍚堣 |  | **690** | **0** |  |

璁惧閲嶈窇鐨勬仮澶嶆潯浠讹細鍦ㄨ澶囩鍏佽 USB 瀹夎/鐐瑰嚮纭锛堝綋鍓嶄袱鍙?MIUI/Android 16 璁惧鍧囪繑鍥?`INSTALL_FAILED_USER_RESTRICTED`锛夛紝鍐嶆墽琛屽師鍛戒护锛?`gradlew '-PmcaDeviceTestVariant=true' '-Pmca.abis=arm64-v8a' :app:connectedDeviceTestAndroidTest
 '-Pandroid.testInstrumentationRunnerArguments.class=...'`锛坰erial device-b 鎴?device-a锛夈€?閲嶈窇鍓嶄笉寰楁妸棣栬疆 6 涓?FAIL 鏀瑰啓鎴?PASS锛涗慨澶嶉」鏈韩浠嶆槸 NOT_RUN銆?
## 缁堝琛ヨ锛氬伐浣滃尯鍥炲綊涓庢渶缁堣瘉鎹紙2026-10-26 04:39锛?
```text
trigger: HANDOFF 閲嶅啓鍚庯紝鎸夆€滀笉寰楁部鐢ㄦ棫缂栬瘧缁撹鈥濋噸璺?:app:compileDebugKotlin + :app:compileDebugAndroidTestKotlin
finding: 褰撳墠宸ヤ綔鍖哄嚭鐜?4 澶勫洖褰掞紝姝ゅ墠璁板綍鐨勪慨澶嶄笉鍦ㄦ枃浠朵腑锛堢枒浼煎叡浜伐浣滃尯琚苟琛屾敼鍔ㄨ鐩栵級锛?  MainViewModel.kt:319 unresolved runtimeProfileWriter锛堢己 alias import锛?  VideoHostAssembly.kt:52 lease lambda 杩斿洖 nullable锛屼笌 adapter 闈炵┖鍙傛暟涓嶇
  LocalVideoWorkerAdapter.kt:16 閿欒鐨勫悓鍖?import com.muyuchat.mca.LocalVideoWorkerService
  VideoWorkerPipeline.kt:80 lastStep 浠嶄负 Int锛屼笌 optLong 姣旇緝澶辫触
fix: 閲嶆柊琛?alias import + receiver 璋冪敤锛沘dapter 绉熺害杩斿洖绫诲瀷鏀?nullable锛涘垹閿欒 import锛沴astStep=-1L
evidence:
  :app:compileDebugKotlin + :app:compileDebugAndroidTestKotlin 鈫?BUILD SUCCESSFUL锛?m33s锛?    459 tasks锛涗袱涓紪璇戜换鍔″潎鐪熷疄 executed锛沚uild/manual-final-compile2.log锛?  python tools/architecture/check_architecture_rules.py 鈫?19/19 PASS
  :core:video-contract:testDebugUnitTest --rerun 鈫?45/45锛? suites锛宖resh XML锛?  :app:testDebugUnitTest --tests 'com.muyuchat.mca.video.*' --rerun 鈫?130/130锛?4 suites锛宖resh XML锛?  :feature:video-workspace:testDebugUnitTest --rerun 鈫?29/29锛? suites锛宖resh XML锛?  :feature:chat:testDebugUnitTest --rerun 鈫?486/486锛?4 suites锛宖resh XML锛?  :core:sd-native:assembleDebug 鈫?BUILD SUCCESSFUL锛堟湰娆＄湡瀹為噸寤?CMake arm64-v8a + x86_64锛?    build/manual-final-native.log锛涙棫 02:13 鏃ュ織鏃╀簬 03:00 鐨?C++ 淇敼锛屽凡搴熷純涓嶇敤锛?  鍚堣 690/0锛涙暟瀛椾笌鏃ц〃鐩稿悓锛屼絾鍏ㄩ儴璇佹嵁涓烘湰杞噸鏂扮敓鎴?unchanged: 璁惧瀹夎闃诲銆佹棤妯″瀷鏉冮噸銆佹棤杩滅 endpoint 鐨?BLOCKED/NOT_RUN 缁撹涓嶅彉
```

---

## 鎺ユ墜楠屾敹杞細璁惧瑙ｉ攣銆佹ā鍨?pin 淇銆佽棰戝叆鍙?缂栬緫鍣ㄧ己闄蜂笌 ComfyUI 寮忕敾甯冿紙2026-10-26锛?
```text
task: V1-06 鐪熷疄璁惧/妯″瀷楠屾敹鏀跺熬锛涚敤鎴疯拷鍔犫€滃墠绔弬鑰?ComfyUI 鍋氭垚鍥惧舰鍖栤€?start_revision: 68b6eef1e67870b02bfef6afda2e625d36663da9锛坆ranch codex/enterprise-refactor锛涙湰杞棤鎻愪氦锛?workspace: 鍏变韩宸ヤ綔鍖猴紝寮€宸ユ椂 666 鏉?porcelain锛涘瓙妯″潡 revision 涓嶅彉锛坙lama.cpp 4ceb1719銆乻d.cpp 16b3846銆乼okenizers acbdc5a锛?devices:
  device-b = 25091RP04C锛圓ndroid 17 / HyperOS锛?2GB RAM锛?data 浣欓噺绾?6GB锛?  device-a = 2304FPN6DC锛圓ndroid 16锛?5.5GB RAM锛?data 浣欓噺绾?61鈥?9GB锛?toolchain: JDK21锛圗:\model\toolchains\jdk-21\jdk-21.0.12.1+1锛夈€丼DK D:\model\android-sdk銆丵NN E:\tools\qairt-sdk\2.45.0.260326锛涘叏閮?Gradle 缁?McaBuildLock
```

### 1. 璁惧瀹夎鏉冮檺锛堝凡瑙ｅ喅锛岄潪浜у搧闄愬埗锛?
- device-b锛氭墜宸?`adb install` 鍦?MIUI `AdbInstallActivity` 鐐瑰嚮鈥滅户缁畨瑁呪€濆悗鎴愬姛锛涘凡鐢?uiautomator 鑷姩鐐瑰嚮骞堕€氳繃锛坅dbd 浠嶆槸 shell锛屾湭鏀逛换浣曡澶囩櫧鍚嶅崟锛夈€?- device-a锛歚adb install`/`pm install` 鎸佺画 `INSTALL_FAILED_USER_RESTRICTED`锛坰hell 鏃犲畨瑁呮潈闄愶級锛屾敼鐢ㄨ澶囪嚜甯︹€滄枃浠剁鐞嗏€濇墦寮€ `/sdcard/Download/*.apk` 鈫?鈥滃厑璁糕€?鈫?鈥滅户缁畨瑁?缁х画鏇存柊鈥濆悗鎴愬姛锛涘畨瑁呮潵婧愭槸鏂囦欢绠＄悊鑰岄潪 shell銆?- 涓ゆ満鍧囨垚鍔熷畨瑁?`com.muyuchat.mca.devicetest` 涓?`com.muyuchat.mca.devicetest.test`锛沺roduction `com.muyuchat.mca` 鏈嵏杞姐€佹湭鏀圭鍚嶃€?- 鎭㈠/鍥為€€锛氫粎瀹夎浜?debug 绛惧悕鐨?devicetest 鍖咃紱production 鍖呮湭鍔ㄣ€?
### 2. 妯″瀷璧勪骇涓?SHA256 pin锛堢湡瀹炵己闄凤紝宸蹭慨澶嶏級

- **缂洪櫡**锛歏1-01/`Wan22Ti2v5bProfiles` 鎶?HF 鐨?`xetHash` 褰撴垚鏂囦欢 sha256銆侶F tree API 鐨勫疄闄呭瓧娈典负 `lfs.oid`锛堟枃浠跺瓧鑺?sha256锛夛紱鏃у€?`f68930ef鈥/`6048e11f鈥/`53b5f294鈥/`9fc99dd5鈥 閮芥槸 xetHash銆?- 瀹樻柟 LFS oid锛?026-10-26 HF API 瀹炲彇锛屽凡鍐欏叆 profile 涓?`MODEL_ASSET_SOURCES.md`锛夛細
  - DiT Q4_0 `fcf40dd62cb5e15556eef5f975700ab0b071aacd1939607689276e88f81ba463`
  - UMT5 Q8_0 `2521d4de0bf9e1cc6549866463ceae85e4ec3239bc6063f7488810be39033bbc`
  - VAE `e40321bd36b9709991dae2530eb4ac303dd168276980d3e9bc4b6e2b75fed156`
  - 澶囬€?DiT Q4_K_M `95b19697鈥銆乁MT5 Q4_K_M `17cf97a5鈥锛堝悓鏃惰ˉ榻愶級
- 璧勪骇浠?ModelScope 鍚屼粨搴撲笅杞斤紙涓?HF 鍚屽瓧鑺傦紱鐩磋繛 HF 浠ｇ悊浠?0.05鈥?MB/s锛孧odelScope 绾?5鈥?2MB/s锛夛紱鏈満 `sha256sum` 涓庝笂杩?LFS oid 瀹屽叏涓€鑷达細
  - VAE 1,409,400,960B锛汥iT 3,029,086,560B锛沀MT5 6,043,068,256B銆?- 宸叉帹閫佸苟鏀惧叆 device-a `files/video_models/wan2.2-ti2v-5b/{dit,text_encoder,vae}`锛坄run-as` 澶嶅埗锛屾潈闄?u0_a451锛夛紱鐪熷疄 worker 鍒拌揪 **閲囨牱涓?*锛岃瘉鏄?manifest 鍏ㄨ鑹?sha256 鏍￠獙涓?native 鍔犺浇閫氳繃銆?- 鍥炲綊锛歚VideoModelAssetVerifierTest` 澧炲姞 primary 涓夐」绮剧‘ oid 鏂█锛?2:08 XML 閫氳繃锛夛紱`MODEL_ASSET_SOURCES.md` 鍖哄垎 sha256 涓?xetHash銆?- 鏈獙璇侊細Q4_K_M/DiT Q8_0 鏈笅杞藉鏍革紱ComfyOrg fp16 璁稿彲浠嶅緟琛ャ€?
### 3. 鏈疆鍙戠幇骞朵慨澶嶇殑浜у搧缂洪櫡

1. **鈥滆棰戔€濆叆鍙ｇ偣鍑绘棤鏁堬紙鐪熷疄缂洪櫡锛?*锛氱敓浜?`ChatScreen` 鐨?actions 鏉ヨ嚜 feature `ChatViewModel.screenActions`锛涘叾 `ChatShellActions` 婕忎簡 `onOpenVideoWorkspace`锛岄粯璁?`{}`銆傚凡鍦?`ChatViewModel` 澧炲姞 `openVideoWorkspace()` 濮旀墭涓庢槧灏勶紝`ChatWorkspaceNavigationTest` 澧炲姞 video 鏂█锛堝畾鍚?JVM 閫氳繃锛夈€備慨澶嶅悗瀹炴祴鎸夐挳鈫掓娊灞?lambda鈫抈selectTab(VIDEO_WORKSPACE)`鈫掗〉闈㈢粍鍚堝叏閮ㄨЕ鍙戙€?2. **缂栬緫鍣ㄤ笉鍙瀵燂紙鐪熷疄缂洪櫡锛?*锛歚VideoGraphEditorState.document/issues` 鏄櫘閫?`var`锛屽弬鏁扮紪杈戝悗椤甸潰涓嶉噸缁勶紙鎻愮ず璇嶈浠嶆樉绀衡€滄彁绀鸿瘝涓虹┖鈥濓級銆傛敼涓?Compose `mutableStateOf`锛涚湡鏈洪獙璇佹憳瑕侀殢杈撳叆鏇存柊锛坄鏂囧瓧杈撳叆锛宎 re ball bouncing鈥锛夈€?3. **杩愯涓棤鍙栨秷鍏ュ彛锛堢湡瀹炵己闄凤級**锛歏M 鏈?`requestCancel(runId)`锛孶I 鏈帴绾裤€傝繍琛屽崱鏂板 `鍙栨秷浠诲姟`锛坄video.run_cancel`锛岄潪缁堟€佸彲瑙侊級锛屽緟鏈€缁?UI 鏋勫缓鍚庤澶囧楠屽彇娑堣矾寰勩€?4. **鐢诲竷涓嶅儚 ComfyUI锛堢敤鎴疯姹傦級**锛氶粯璁よ鍥炬敼涓?`ADVANCED`锛涚敾甯冭ˉ鐐归樀鑳屾櫙銆佽礉濉炲皵杩炵嚎銆佹寜鎷撴墤娣卞害鑷姩 DAG 甯冨眬銆佽妭鐐规爣棰樿壊/绔彛鏍囩銆佺缉鏀?澶嶄綅宸ュ叿銆乣鑺傜偣鍒楄〃` sheet锛涙ā鏉胯妭鐐逛綅缃敼涓哄乏鈫掑彸锛涗慨姝?dp鈫抪x 鎹㈢畻锛堟鍓嶅崱鐗囬噸鍙狅級銆傛埅鍥?`build/evidence/v1-06/device-b-graph-light-v3.png`銆乣device-b-graph-final-light.png` 鏄剧ず鑺傜偣宸叉寜 7 鍒楁帓甯冿紝鏂囧瓧/绔彛浠嶆湁涓€杞孩鍑轰慨澶嶅緟鏈€缁堟瀯寤哄鏍搞€?
### 4. 鐪熷疄璁惧 instrumentation锛堜慨澶嶅悗澶嶈窇锛?
| 璁惧 | 鏋勫缓 | 鍛戒护 | 缁撴灉 |
| --- | --- | --- | --- |
| device-b | app 11:37/11:50 + test 11:52/12:06锛涙棫绠€鍖?UI | `am instrument ... 5 涓棰戠被` | **OK (9 tests)**锛堥杞?3 PASS/6 FAIL 淇濈暀锛?|
| device-a | app 11:59 + test 11:54锛涙棫绠€鍖?UI | 鍚屼笂 | **OK (9 tests)** |
| device-b | 鐢诲竷 UI build锛?2:38/12:40锛?| 鍚屼笂 | 寰呭璺戯紙鏈€缁?dp 淇鏋勫缓鍚庯級 |

- 棣栬疆 6 涓?FAIL 鐨勭湡瀹炰慨澶嶅潎鍦ㄥ満锛歟ncoder capability+128脳128銆佸彇娑堟柇瑷€娆″簭銆乸ublisher 鐪熷疄缂栫爜杈撳叆銆?0鈫?2 杩佺Щ鏂█銆乶ative 鏃犳晥璇锋眰銆乁iAutomator 鏇夸唬 Espresso銆?- UI 娴嬭瘯澶瑰叿鍙︿慨锛氬惎鍔ㄧ敤 `am start -W -n`锛堝悗鍙板惎鍔ㄩ檺鍒讹級銆佷竴娆℃€ф洿鏂板脊绐椻€滅◢鍚庘€濄€佹寜 bounds 閫夋嫨 sheet 瀛楁銆佺敤 scrim 鍏抽棴 bottom sheet銆?- 鍘熷 XML/鏃ュ織锛歚build/device-run3-instrument.log`銆乣build/device-device-a-run3-instrument.log`銆乣build/device-run2-instrument.log`銆乣build/device-device-a-run2-instrument.log`銆乣build/ui-test-debug.log`銆?
### 5. 鐪熷疄 native 鎵ц锛堥儴鍒嗚瘉鎹紝灏氭棤鍑虹墖锛?
- **20 姝ュ熀绾?*锛?80脳832脳33銆乪uler銆乬uidance 6.0銆乫low 3.0銆?0 姝ワ紝鐪熷疄鎻愮ず璇嶏級锛氱姸鎬?`鍑嗗涓啋閲囨牱涓璥锛涚害 9 鍒嗛挓杩涘害浠嶄负 `姝ラ 0/20`锛泈orker `:local_video` 绾?400% CPU銆丳SS 浠?5.3GB 鍗囧埌 7.1GB锛沗dumpsys` 鏄剧ず gfx/wm 鏃犲穿婧冦€傞殢鍚?force-stop 妯℃嫙杩涚▼姝讳骸 鈫?閲嶆柊杩涘叆瑙嗛椤垫樉绀?**宸蹭腑鏂紙INTERRUPTED锛?*锛岄樁娈碘€滈噰鏍封€濓紝璧勬簮鈥滈噴鏀炬湭纭鈥濓紝worker 杩涚▼娑堝け銆丮emAvailable 鍥炲埌绾?9.5GB锛涙湭鐢熸垚 MP4銆?- **1 姝ュ熀绾?*锛?80脳832脳33銆乪uler銆? 姝ワ紝鎻愮ず璇?鈥渁 re ball bouncing鈥︹€濓級锛氭彁浜ゅ悗 `鍑嗗涓啋閲囨牱涓璥锛涙浘璇诲埌娈嬬暀杩涘害 `825/1263`锛岄噸缃负鏂拌繍琛?`0/1`锛涘崟姝ユ寔缁?>9 鍒嗛挓銆丳SS 7.0GB銆丆PU 浠嶆椿璺冿紱鍐欎綔鏃舵湭瀹屾垚锛屾湭鐢熸垚 MP4銆?- **95 缂烘ā鍨嬭瘹瀹炲け璐?*锛?8a锛堟棤妯″瀷锛夋彁浜ゅ悗 `MODEL_MISSING`锛屾槑缁嗘寚鍚?`Wan2.2-TI2V-5B-Q4_0.gguf`锛岃祫婧愬凡閲婃斁锛涙槸鐪熷疄閿欒璺緞 PASS銆?- **鎬ц兘缁撹锛堝疄娴嬶級**锛歡uidance 6.0 姣忔鍚?cond+uncond 涓ゆ T5/DiT锛?80脳832脳33 鐨勫崟姝ュ嵆闇€绾?10 鍒嗛挓绾э紝20 姝ュ熀绾垮湪 4 鏍?CPU 涓婁负灏忔椂绾э紝涓嶅叿澶囩Щ鍔ㄧ瀹炵敤鎬с€備骇鍝侀绠楀洜姝?*涓嶈兘**鎸夐粯璁?480脳832脳33脳20 鍐荤粨涓哄彲浜や粯锛涙渶灏忓悎娉?smoke 涓?64脳64脳9脳1锛堜唬鐮佹牎楠岋細32 鍊嶆暟銆?k+1锛夛紝浣嗘湰杞皻鏈骇鍑?MP4銆?- 鏈獙璇侊細鐪熷疄甯у簭鍒?MP4銆佹挱鏀俱€佸垎浜€佸彟瀛樸€佸垹闄ゃ€佸彇娑堬紙UI 鏂版寜閽級銆侀噸澶嶈繍琛屻€佽繘绋嬫仮澶嶅悗鐨勯噸璇曘€佺儹鐘舵€佷笌鍐呭瓨宄板€煎父閲忔€с€?
### 6. 鍥炲綊涓庤繙绔?
- 瀹氬悜 JVM锛歚ChatWorkspaceNavigationTest` + `VideoModelAssetVerifierTest` 鍦ㄩ攣鍐呴€氳繃锛坄build/manual-jvm-targeted.log`锛孊UILD SUCCESSFUL锛夈€?- 鍏ㄩ噺 JVM 690/鏋舵瀯 19锛氭湰杞湭閲嶈窇锛堟瀯寤洪攣琚叾浠?lane 鍗犵敤/鏃堕棿涓嶈冻锛夛紝娌跨敤 04:39 璇佹嵁骞舵爣娉ㄩ潪鏈疆銆?- Room 杩佺Щ锛歚VideoRoomMigrationTest` 鍦?9 椤逛腑閫氳繃锛?9鈫?2銆?0鈫?2锛夛紱鍥剧墖/鑱婂ぉ/DSH 鏈綔鏈疆鐪熸満鍥炲綊銆?- 杩滅 ComfyUI锛氭湰鏈?8188/8189/8000 鏃犵洃鍚€佹棤 ComfyUI 浠撳簱/杩涚▼銆佹枃妗ｅ彧鏈夌ず渚嬪湴鍧€锛涚湡瀹炴彁浜?WS/history/涓嬭浇/鍙栨秷 **BLOCKED**锛堟棤鍥哄畾 revision endpoint锛夛紝鏈吉閫犮€?
### 7. 鏈疆鐭╅樀

| 椤?| 鐘舵€?| 璇佹嵁/缂哄彛 |
| --- | --- | --- |
| 璁惧瀹夎锛堜袱鏈猴級 | **PASS** | devicetest + test APK 宸茶锛?8a adb 纭妗嗐€乫9d 鏂囦欢绠＄悊璺緞 |
| 璧勪骇瑙掕壊/灏哄/SHA256 | **PASS** | 瀹樻柟 LFS oid + 鏈湴 sha256 涓€鑷达紱device-a worker 閫氳繃鏍￠獙骞惰繘鍏ラ噰鏍?|
| 缂轰欢/鍚屽悕閿欎欢澶辫触 | **PASS锛堢己浠讹級** | device-b MODEL_MISSING锛涘悓鍚嶉敊浠?鎹熷潖浠?JVM 瑕嗙洊锛岃澶囨湭鍗曠嫭澶嶇幇 |
| 瑙嗛鍏ュ彛/缂栬緫鍣ㄥ彲瑙傚療鎬?| **PASS** | 鍏ュ彛鏃ュ織閾?+ 鎽樿瀹炴椂鏇存柊锛堢敾甯?UI 鏈€缁堟瀯寤哄緟澶嶈窇 UI 娴嬭瘯锛?|
| 9 椤?instrumentation锛堜慨澶嶅悗锛?| **PASS锛堟棫绠€鍖?UI锛屼袱鏈猴級** | OK (9 tests) 脳2锛涢杞?FAIL 淇濈暀 |
| 鐪熷疄 native load/condition/sample 鍚姩 | **PASS锛堥儴鍒嗭級** | worker 400% CPU銆丳SS 7.1GB銆佽繘鍏?SAMPLE |
| 鐪熷疄 T2V 鍑虹墖锛圡P4/甯?灏哄锛?| **NOT_RUN** | 20 姝ヨ窇鏈湪 9 鍒嗛挓鍐呭畬鎴愰姝ワ紱1 姝ヨ窇鏈畬鎴愶紱鏃?MP4 |
| I2V | **NOT_RUN** | 杈撳叆鍥惧凡澶囷紙`/sdcard/Pictures/mca-i2v.png`锛夛紝鏈墽琛?|
| 鍙栨秷/閲嶈瘯/閲嶅杩愯 | **NOT_RUN** | 鍙栨秷 UI 鍒氳ˉ锛涙湰杞粎楠岃瘉杩涚▼姝讳骸鈫扞NTERRUPTED |
| 鎾斁/鍒嗕韩/鍙﹀瓨/鍒犻櫎 | **NOT_RUN** | 鏃犵湡瀹炰骇鐗?|
| 杩涚▼姝讳骸鎭㈠ | **PASS锛堣瀵燂級** | force-stop 鈫?INTERRUPTED銆佽祫婧愰噴鏀炬湭纭銆佸唴瀛樺洖鏀?|
| V1-06 瑙嗚/鏃犻殰纰?涓よ疆 QA | **NOT_RUN锛堣繘琛屼腑锛?* | 娴呰壊鐢诲竷鎴浘宸叉湁锛涙繁鑹?闀挎枃鏈?閿洏/TalkBack/涓よ疆寰呭仛 |
| 杩滅 ComfyUI | **BLOCKED** | 鏃?endpoint/鍥哄畾 revision |
| V1 瀹屾垚鍒ゅ畾 | **鏈畬鎴?* | 鐪熷疄鍑虹墖涓?V1-06 鏈弧瓒?|

### 8. 鍥為€€

- 鏈疆浠ｇ爜淇敼鍙寜鏂囦欢鍥為€€锛歚ChatViewModel.kt`锛堣棰戝鎵橈級銆乣VideoRunStatusSection.kt`锛堝彇娑堟寜閽級銆乣VideoGraphCanvas.kt`/`VideoWorkspaceScreen.kt`/`VideoWorkflowTemplates.kt`锛堢敾甯冿級銆乣VideoGraphEditorState.kt`锛坢utableStateOf锛夈€乣Wan22Ti2v5bProfiles.kt`/`MODEL_ASSET_SOURCES.md`/娴嬭瘯锛坧in锛夈€?- 璁惧渚т粎瀹夎 debug-signed devicetest锛涙ā鍨嬫枃浠跺湪 device-a app 绉佹湁鐩綍锛屽彲 `run-as rm` 娓呯悊锛沺roduction 鍖呬笌鐢ㄦ埛鏁版嵁鏈姩銆?- 鏈彁浜ゃ€佹湭鎺ㄩ€併€佹湭鍙戝竷銆?
## 楠屾敹鏀跺熬杞細鐪熷疄鍑虹墖銆佸彇娑堛€佸獟浣撳姩浣滀笌 V1-06锛?026-10-26 13:45锛?
鏈疆鍦ㄥ悓涓€宸ヤ綔鍖虹户缁紝鏈€缁?APK 涓?`app-deviceTest.apk`锛?3:10锛宻ha256 `52926fea5d06b9f4c82567e7ea2eb5bbcfd4cca1b0773e8a4000b9a32226cacf`锛夛紝宸插畨瑁呭埌 device-b锛?3:10:44锛変笌 device-a锛?3:22:52锛夈€傛簮鐮?HEAD `68b6eef1e67870b02bfef6afda2e625d36663da9`锛屽叏閮ㄦ敼鍔ㄤ粛鍦ㄥ伐浣滃尯锛屾湭鎻愪氦/鎺ㄩ€併€?
### 1. 鏈疆鏂板浜у搧淇锛堢敾甯冧笌鏃犻殰纰嶏級

- 鑺傜偣鍗℃敼涓衡€滄瘡绔彛涓€琛屸€濓細杈撳叆/杈撳嚭鏍囩鍚勮嚜鍗?22dp 琛岋紝绔彛鐐规寜 dp鈫抪x 绮剧‘钀藉湪瀵瑰簲琛屼腑蹇冿紱鑺傜偣瀹?260dp銆備慨澶嶄簡鏃ф瀯寤轰腑绔彛鐐硅惤鍦ㄥ崱鐗囦笅鏂广€佹爣绛炬埅鏂?閿欒鐨勭湡瀹?UI 缂洪櫡銆?- 绔彛鐐逛笌鑺傜偣琛?`Role.Button` + `onClick` 璇箟锛泆iautomator 鏍戜腑 8 涓鍙ｅ潎涓?`clickable=true`锛岃妭鐐瑰垪琛紙鎸夐挳寮忚繛绾匡級浠嶄繚鐣欎负 TalkBack 鏇夸唬璺緞銆傜鍙ｇ偣楂樺害浠嶄负 22dp锛堜綆浜?48dp 鎸囧崡锛夛紝鍒椾负宸茬煡鏃犻殰纰嶉檺鍒躲€?- 绗簩杞瑙?QA 璇佹嵁锛歚build/evidence/v1-06/device-b-final-light-canvas.png`銆乣device-b-final-dark-canvas.png`銆乣device-b-qa2-panned.png`銆乣device-b-final-node-sheet-light.png`銆乣device-b-longtext-keyboard-light.png`銆乣device-b-longtext-summary-light.png`锛涜涔夋爲 `device-b-final-graph.xml`銆?
### 2. 鐪熷疄鍑虹墖涓庤澶囧姩浣滐紙鍏ㄩ儴缁忕敓浜?`MainActivity` 瑙嗛鍏ュ彛锛?
| 鍔ㄤ綔 | 缁撴灉 | 鍘熷璇佹嵁 |
| --- | --- | --- |
| T2V 64脳64脳9脳1锛堟渶缁?APK锛?| **PASS**锛?05s锛屽嘲鍊?PSS 8,511,619KB锛孋OMPLETED/RELEASED锛汳P4 9 甯с€?4脳64銆?6fps銆乭264銆?.5625s锛宻ha256 `8c87545472d9c1dc32970ce49726c21d6b4a384199c2e2e80b246fa60e105e05`锛涢€愬抚鍧囧€?鏍囧噯宸?甯ч棿宸瘉鏄庨潪绌轰笖鍙樺寲 | `final-apk-real-t2v.txt`銆乣final-real-t2v-driver.log`銆乣final-apk-real-64x64x9x1.mp4`銆乣frames-final/` |
| I2V 64脳64脳9脳1 | **PASS**锛?91s锛屽嘲鍊?8,566,695KB锛孋OMPLETED/RELEASED锛涘揩鐓у惈 `imageRef`锛坄content://.../Pictures/mca-i2v.png`锛夛紱MP4 9 甯с€?4脳64銆?6fps锛宻ha256 `84ea97377be381ebb7d1cd47665fc29a524bcd5e78d580d0770eb8a39655c355`锛坒9d 11:59 鏋勫缓锛涙渶缁?13:10 APK 鏈噸璺?I2V锛?| `i2v-64x64-result.txt`銆乣i2v-64x64-driver.log`銆乣real-i2v-64x64x9x1.mp4`銆乣frames-i2v/` |
| 閲嶅杩愯锛? 姝?T2V锛岀儹鎬侊級 | **PASS**锛?40s锛屽嘲鍊?8,566,695KB锛孋OMPLETED/RELEASED锛涜繘搴?0/2鈫?/2鈫?/2锛涘獟浣?sha256 `46d175c58ef8b453a2b261750afc93580ff24b97e690038677bc95bb95a09469`锛坒9d 11:59 鏋勫缓锛?| `db-device-a-final.db`锛坮un `v-0846e69c鈥锛?|
| 鐢ㄦ埛鍙栨秷锛? 姝?T2V锛?| **PASS**锛欳ANCEL_REQUESTED/STOPPING 鈫?194s 鍚?CANCELLED/RELEASED锛岄敊璇爜 `CANCELLED_AFTER_NATIVE_STOP`锛涙湭鍙戝竷濯掍綋 | `final-cancel-result.txt`銆乣device-a-final-cancel-before/requested/result.png`銆丏B events |
| 鎾斁 | **PASS**锛氫綔鍝佸簱鈫掓挱鏀惧櫒鏄剧ず 64脳64/16fps/00:00锛屾挱鏀?杩涘害鎺т欢鍙氦浜?| `device-a-player.png` |
| 鍒嗕韩 | **PASS**锛氱郴缁熷垎浜潰鏉垮垪鍑哄皬绫冲垎浜?QQ/钃濈墮/鐭俊 | `device-a-share-sheet.png` |
| 鍙﹀瓨 | **PASS**锛氱粡绯荤粺鐩綍閫夋嫨鍐欏叆 `/sdcard/Download/MCA_video_20261026_130703.mp4`锛宻ha256 涓庡師鐗囧畬鍏ㄤ竴鑷达紙`d0807293鈥锛?| `exported-MCA_video_20261026_130703.mp4` |
| 鍒犻櫎 | **PASS**锛氱‘璁ゅ脊绐楀悗鍒犻櫎 MCA 绉佹湁鍓湰锛涘凡瀵煎嚭鍓湰淇濈暀锛涗綔鍝佸簱鍥炲埌绌烘€侊紱濯掍綋琛岃娓呴櫎 | `device-a-delete-confirm.png`銆乣device-a-after-delete.png` |
| 杩涚▼姝讳骸鎭㈠ | **PASS锛堣瀵燂級**锛?0 姝?1 姝ュ熀绾胯 force-stop 鎴?20 鍒嗛挓 watchdog 缁堟鍚庯紝閲嶈繘椤甸潰涓?INTERRUPTED锛泈atchdog 瑙﹀彂 `cancelAndExit`锛孶I/DB 鏄剧ず璧勬簮鈥滈噴鏀炬湭纭鈥?| `db-device-a-final.db` 椤堕儴涓ゆ潯 INTERRUPTED |
| 9 椤?instrumentation锛堟渶缁?APK锛?| **PASS脳2**锛?8a37aa7 `OK (9 tests)`锛坄device-run7-instrument.log`锛夛紱device-a `OK (9 tests)`锛坄device-device-a-run4-instrument.log`锛夛紱棣栬疆 3 PASS/6 FAIL 淇濈暀 | 鏃ュ織瑙佸乏 |

### 3. 浜у搧瑙勬牸瀹炴祴涓庨绠楋紙鏈揪鏍囷級

- 480脳832脳33脳1锛堥粯璁ゅ嚑浣曘€? 姝ワ級鍦?12:55:57 琚?**20 鍒嗛挓杩涚▼ watchdog** 缁堟锛氱姸鎬佲€滃凡涓柇鈥濓紝闃舵鈥滈噰鏍封€濓紝`姝ラ 0/1` 鎸佺画绾?23 鍒嗛挓锛屽嘲鍊?PSS 8,328,327KB锛屽嘲鍊兼俯搴?40.9掳C锛涙棤 MP4銆傚搴?`CANCELLED_AFTER_NATIVE_STOP` 涔嬪墠鐨?watchdog 璺緞涓?`INTERRUPTED_PROCESS_DEATH` 璁板綍鍦?`db-device-a-final.db`銆?- 480脳832脳33脳20锛堥粯璁ゆ鏁帮級鍦ㄧ害 9 鍒嗛挓鍐呬粛涓?`姝ラ 0/20`锛孭SS 7.1GB锛涙湭瀹屾垚銆?- 64脳64脳9脳1 鏄綋鍓?CPU 璁惧鐨勬渶灏忓悎娉曞彲瀹屾垚瑙勬牸锛氱害 5 鍒嗛挓銆佸嘲鍊?8.5鈥?.1GB銆佹俯搴?40.7鈥?1.6掳C锛? 姝ョ儹鎬佺害 4 鍒嗛挓锛涘彇娑堝埌纭鐗╃悊鍋滄骞堕噴鏀剧害 194s銆?- 缁撹锛?*涓嶈兘鎶婇粯璁?480脳832脳33脳20 鍐荤粨涓哄彲浜や粯浜у搧棰勭畻**锛沄1-06 鐨勨€滃€欓€夎鏍笺€佽祫婧?鑰楁椂/鍋滄/鐑?閲嶅杩愯棰勭畻鍦ㄩ獙鏀跺墠鍐荤粨骞舵弧瓒斥€濇潯浠?**NOT_MET**銆傚綋鍓嶄粎鑳界О瀹為獙闂幆锛圕PU 姝ｇ‘鎬?+ 鏈€灏忚鏍煎嚭鐗囷級锛屼笉鑳界О绉诲姩绔骇鍝佸彲鐢ㄣ€?- 鍔犻€熻矾寰勬牳鏌ワ細`core/sd-native/src/main/cpp/CMakeLists.txt` 寮哄埗 `SD_VULKAN=OFF`銆乣GGML_VULKAN=OFF`銆乣GGML_BACKEND_DL=OFF`锛屽綋鍓?APK 娌℃湁 Vulkan/OpenCL/NPU 鍚庣锛涜嫢瑕佹弧瓒充骇鍝侀绠楋紝蹇呴』鍏堝疄瑁呭苟楠岃瘉鍔犻€熷悗绔紙鏈崱鏈仛锛屽睘鍚庣画鍒囩墖锛夈€?
### 4. 鏈疆鍙戠幇鐨?UI 鐘舵€佺己闄凤紙鏈慨澶嶏紝璁板綍锛?
- 鍚屼竴 worker 杩涚▼鍐呭紑濮嬫柊浠诲姟鏃讹紝杩愯鍗″湪棣栦釜鐪熷疄閲囨牱鍥炶皟鍓嶄細鏄剧ず涓婁竴浠诲姟鐨?`姝ラ 825/1263`锛堟棫 20 姝ヨ繘搴︼級锛岃€屾柊浠诲姟瀹為檯浠嶅湪鍔犺浇锛涚害 105鈥?10s 鍚庢墠閲嶇疆涓?`0/1` 鎴?`0/2`銆傞樁娈典篃浼氬湪妯″瀷鍔犺浇鍓嶆彁鍓嶆樉绀衡€滈噰鏍蜂腑鈥濄€?- 褰卞搷锛氳繘搴?闃舵瀵圭敤鎴疯瀵硷紱涓嶆敼鍙樺獟浣撴纭€с€佸彇娑堝彈鐞嗘垨璧勬簮閲婃斁缁撴灉銆備慨澶嶉渶鍦?native/worker 姣忔寮€濮嬫椂娓呴浂 progress 鍥炶皟骞跺欢杩?SAMPLING 闃舵涓婃姤锛屽睘鍚庣画鍒囩墖銆?- 鍙︼細draft 鍙湪鍐呭瓨涓紝杩涚▼閲嶅惎鍚庡洖鍒版ā鏉匡紙鎻愮ず璇?鍑犱綍涓㈠け锛夛紱鏈澶氭 force-stop 鍚庡鐜帮紝鏈敼鏁版嵁灞傘€?
### 5. 鍥炲綊涓庨棬绂?
- 鏋舵瀯锛歚python tools/architecture/check_architecture_rules.py` 鈫?**19/19 PASS锛圗XIT=0锛?3:32 鏈疆 fresh锛?*銆?- JVM锛歚VideoModelAssetVerifierTest` 13:35 fresh XML 閫氳繃锛沗ChatWorkspaceNavigationTest` 鍦?`--rerun-tasks` 鏋勫缓涓噸缂栬瘧鏈畬鎴愶紝**涓嶈兘璁颁负鏈疆 PASS**锛坒ull 690 鍚屾牱鏈噸璺戯級銆?- 璁惧锛? 椤硅鐩栫紪鐮併€佸彂甯冦€丷oom 29鈫?2 杩佺Щ銆乶ative bridge銆乁I锛涘浘鐗?鑱婂ぉ/DSH 鐨勭湡瀹炲啋鐑熸湰杞?**NOT_RUN**锛堜粎鏋舵瀯/鏃㈡湁 JVM锛夈€?- 杩滅 ComfyUI锛氫粛 **BLOCKED**锛堟棤鍥哄畾 revision endpoint锛夛紝鏈吉閫犮€?
### 6. 鏈€缁堢煩闃?
| 椤?| 鐘舵€?| 璇佹嵁/缂哄彛 |
| --- | --- | --- |
| 璁惧瀹夎/鏈€缁?APK 涓ゆ満 | **PASS** | device-b 13:10:44銆乫9d 13:22:52锛?/9脳2 |
| 璧勪骇瑙掕壊/鐗堟湰/璁稿彲/SHA256 | **PASS** | 瀹樻柟 LFS oid + 鏈湴 sha锛涚己浠垛啋MODEL_MISSING |
| 鐪熷疄 T2V/I2V 鍑虹墖锛堟渶灏忚鏍硷級 | **PASS锛堝疄楠岋級** | 64脳64脳9脳1锛汳P4/甯?灏哄/sha 瑙?搂2 |
| 浜у搧瑙勬牸 T2V锛?80脳832脳33脳20锛?| **FAIL/NOT_MET** | 1 姝?>20min 琚?watchdog 缁堟锛?0 姝ュ皬鏃剁骇 |
| 鎾斁/鍒嗕韩/鍙﹀瓨/鍒犻櫎 | **PASS** | 瑙?搂2锛涘鍑哄瓧鑺備竴鑷达紝鍒犻櫎涓嶅奖鍝嶅鍑哄壇鏈?|
| 鍙栨秷/閲婃斁/閲嶅杩愯 | **PASS** | 鍙栨秷 194s 鐗╃悊鍋滄骞?RELEASED锛涜繛缁?4 娆¤繍琛屽潎閲婃斁 |
| 杩涚▼姝讳骸鎭㈠ | **PASS锛堣瀵燂級** | INTERRUPTED锛涜寮烘潃鏃堕噴鏀炬湭纭 |
| V1-06 UI/瑙嗚/鏃犻殰纰?涓よ疆 QA | **PASS锛圲I 瀛愰泦锛?* | 娴?娣辫壊銆侀暱鏂囨湰銆侀敭鐩樸€佺鍙ｈ涔夈€佷袱杞埅鍥?|
| V1-06 浜у搧棰勭畻鍐荤粨骞舵弧瓒?| **NOT_MET** | 榛樿 480脳832脳33脳20 涓嶅彲浜や粯锛涗粎鏈€灏忓疄楠岃鏍?|
| 鐪熷疄杩滅 ComfyUI | **BLOCKED** | 鏃?endpoint/鍥哄畾 revision |
| V1 瀹屾垚鍒ゅ畾 | **鏈畬鎴?* | 鐪熷疄鏈€灏忓嚭鐗囦笌璁惧鍔ㄤ綔宸查€氳繃锛屼絾浜у搧棰勭畻/V1-06 鏈弧瓒?|

### 7. 鍥為€€涓庢仮澶嶆潯浠?
- 鏈疆浠ｇ爜锛歚feature/video-workspace/.../VideoGraphCanvas.kt`锛堣妭鐐瑰崱/绔彛/璇箟锛夈€傚洖閫€璇ユ枃浠跺嵆鍥炲埌涓婁竴鐗堢敾甯冿紝涓嶅奖鍝?owner/native/濯掍綋鏁版嵁銆?- 璁惧锛氫粎鍗囩骇 debug-signed `com.muyuchat.mca.devicetest`锛涙ā鍨嬩笌鍘嗗彶鍦?app 绉佹湁鐩綍锛沺roduction `com.muyuchat.mca` 鏈姩銆俧9d 浠嶅紑鍚?`svc power stayon true`锛堟祴璇曠敤锛屽彲 `svc power stayon false` 鎭㈠锛夈€?- 浜у搧瑙勬牸楠屾敹鎭㈠鏉′欢锛氭彁渚涘彲鐢ㄥ姞閫熷悗绔紙GPU/NPU锛夋垨闄嶄綆榛樿鍑犱綍/姝ユ暟骞堕噸鏂板喕缁撻绠楋紱鍦ㄥ喕缁撳墠涓嶅緱鎶?64脳64 瀹為獙鍑虹墖褰撲綔浜у搧鍙敤銆?- 杩滅鎭㈠鏉′欢锛氬浐瀹?ComfyUI revision 鐨勫彲杈?endpoint 涓庡嚟鎹€?
## 鍔犻€熷皾璇曡疆锛歄penCL锛?026-10-26 15:45锛?
### 1. 鏋勫缓涓庢帴鍏?
- 閫夌敤 sd.cpp/ggml 鑷甫 OpenCL 鍚庣锛坄SD_OPENCL`/`GGML_OPENCL`锛孉dreno kernels锛孫penCL 3.0锛孮NN SDK 鐨?Khronos 澶存枃浠讹紱璁惧 public `/vendor/lib64/libOpenCL.so` 鎷夊埌 `build/opencl-exp/lib` 浠呯敤浜庨摼鎺ワ級銆?- 瀹炴祴璁惧 `uses_libraries=libcdsprpc.so:libOpenCL.so:libadsprpc.so`锛宍libOpenCL.so` 鍦?`/vendor/etc/public.libraries.txt`锛涙墦鍖呮帓闄?vendor lib锛坄packaging.jniLibs.excludes += "**/libOpenCL.so"`锛夛紝APK 涓嶅啀鍒嗗彂鍘傚晢搴撱€?- 甯?OpenCL 鐨?APK锛歚f351760d鈥锛堝惈 vendor lib锛屼粎瀹為獙锛夆啋 鎺掗櫎鍚?`76a28fce鈥锛沗device-device-a-run6` 浠嶄负 **OK (9 tests)**銆?- 鍚庣閫夋嫨锛歚backend="auto,te=cpu,vae=cpu"`锛宍params_backend="cpu"`锛宑ontext 鍒涘缓澶辫触鏃跺洖閫€ CPU銆?
### 2. 鐪熸満缁撴灉锛欶AIL锛坣ative abort锛?
- 杩愯 `v-6af9ab66-cc3f-4b9b-868c-6db8b74b68b9`锛?4脳64脳9脳1锛宻teps=1锛宲rompt 姝ｅ父锛夛細`CONDITIONING鈫扴AMPLING`锛?71s 绗竴娆＄湡瀹?`0/1`锛?87s 杩涚▼姝讳骸锛涘嘲鍊?PSS 5,366,901KB锛孧emFree 93MB銆乻wap 4.2GB銆乲swapd 娲昏穬锛?*鏃犲獟浣?*銆?- `dumpsys activity exit-info`锛歚reason=5 (APP CRASH(NATIVE)) status=6`锛坰ignal 6锛夈€?- crash backtrace锛歚abort 鈫?ggml_abort 鈫?ggml_backend_opencl_graph_compute 鈫?ggml_backend_graph_compute 鈫?GGMLRunner::execute_graph 鈫?WAN::WanRunner::compute 鈫?StableDiffusionGGML::sample 鈫?sample_k_diffusion 鈫?generate_video`銆?- 鍒ゅ畾锛歄penCL 鍚庣鍦?Wan 棣栦釜閲囨牱鍥炬墽琛屾椂涓诲姩 abort锛沜ontext 鍒涘缓鐨勫洖閫€涓嶈鐩栬繍琛屾椂宕╂簝銆傚師濮嬫憳瑕侊細`build/evidence/v1-06/opencl-crash-summary.txt`銆?
### 3. 鍥為€€涓?CPU 鎭㈠

- 鍥為€€ `SD_OPENCL/GGML_OPENCL OFF`銆乣ctx_params.backend="cpu"`銆佸垹闄ゅご鏂囦欢涓庢墦鍖呮帓闄わ紱CPU 閲嶅缓 `96d18df6鈥锛堟棤 libOpenCL锛夛紝15:36 瑁?device-a锛宍device-device-a-run7` **OK (9 tests)**銆?- CPU 鎭㈠杩愯 `v-1bcfaac4鈥锛?4脳64脳9脳1锛夛細235s锛屽嘲鍊?8,679,311KB锛孋OMPLETED/RELEASED锛汳P4 9 甯?64脳64/16fps锛宻ha `ed597c5db433ec11181980387e9f1ac9039f31f66fb5bbdd81844f525c415467`锛坄cpu-restore-64x64x9x1.mp4`锛夈€?
### 4. 缁撹涓庢仮澶嶆潯浠?
- OpenCL 鍔犻€?**FAIL**锛涘綋鍓?Wan2.2 5B + sd.cpp OpenCL 鍚庣鍦?device-a 涓嶅彲鐢ㄣ€俈1-06 浜у搧棰勭畻浠?**NOT_MET**銆?- 涓嬩竴姝ュ姞閫熼€夐」锛歏ulkan锛坓gml Vulkan 闇€ glslc/Vulkan SDK 涓?shader 鐢熸垚锛涙湭鎷夊彇/鏈瀯寤猴級銆丵NN/NPU锛堟湭鎺ュ叆 sd.cpp锛夈€佹垨鎹㈢‖浠讹紱鍦ㄥ姞閫熷彲鐢ㄥ墠 V1 淇濇寔鏈畬鎴愩€?- 鍥為€€鐐癸細CPU 鏋勫缓 `96d18df6鈥 宸叉仮澶嶅苟楠岃瘉锛汷penCL 瀹為獙 APK/鏋勫缓浜х墿涓嶈繘鍏ヤ骇鍝佺粨璁恒€?

## 鍔犻€熷皾璇曡疆锛歏ulkan锛?026-10-26 16:45锛?
### 1. 鏋勫缓涓庢帴鍏?
- 澶嶇敤 NDK 鐨?`shader-tools/windows-x86_64/glslc.exe`銆丯DK Vulkan C 澶存枃浠?`libvulkan.so` stub锛岃ˉ Khronos `Vulkan-Hpp` 涓?`SPIRV-Headers`锛沨ost shader generator 鐢?VS 2022 BuildTools 鐨?cl锛坄GGML_VULKAN_SHADERS_GEN_TOOLCHAIN=build/vulkan-host-toolchain.cmake`锛夈€?- 鍏堢嫭绔嬫瀯寤?`ggml-vulkan` 鎴愬姛锛坄libggml-vulkan.a`锛夛紝鍐嶄互 `SD_VULKAN=ON`/`GGML_VULKAN=ON` 鏋勫缓 app锛沗device-a` 涓?APK `b36bce97鈥锛宍libmca_sd_native.so` 鐨?`DT_NEEDED` 鍚?`libvulkan.so`锛孉PK 鏈墦鍖?NDK stub锛坄packaging.jniLibs.excludes`锛夛紝璁惧 `/system/lib64/libvulkan.so` 瑙ｆ瀽鎴愬姛銆?- 9 椤硅澶囨祴璇曪細瑙ｉ攣鍚?`OK (9 tests)`锛坄build/device-device-a-run9-instrument.log`锛夛紱姝ゅ墠涓€娆?8/9 鐨勫け璐ユ槸璁惧閿佸睆瀵艰嚧 `chat did not become visible`锛屼笉鏄?Vulkan 鍥炲綊銆?
### 2. 鐪熸満缁撴灉锛欶AIL锛圓dreno Vulkan 椹卞姩 SIGSEGV锛?
- 杩愯 `v-048844bb-31f7-4409-888e-edc6025cda0c`锛?4脳64脳9脳1锛宻teps=1锛宲roduction `MainActivity`锛夛細鐘舵€?`SAMPLING` 鍚庤繘绋嬫浜★紝DB `INTERRUPTED`/`INTERRUPTED_PROCESS_DEATH`锛屾棤 step銆佹棤濯掍綋銆?- `dumpsys activity exit-info`锛?6:29:49.957 pid 26752 `reason=5 (APP CRASH(NATIVE)) status=11`銆?- logcat锛歚Fatal signal 11 (SIGSEGV), code 1, fault addr 0x8 in tid 26774 (DefaultDispatch)`锛涙爤 `qglinternal::vkCmdBindPipeline`锛坄/vendor/lib64/hw/vulkan.adreno.so`锛夆啇 `ggml_vk_dispatch_pipeline<vk_mat_mat_push_constants>` 鈫?`ggml_vk_mul_mat_q_f16` 鈫?`ggml_vk_graph_compute` 鈫?`WAN::WanRunner::compute`銆傛憳瑕侊細`build/evidence/v1-06/vulkan-crash-summary.txt`銆?
### 3. 鍥為€€涓?CPU 鎭㈠

- 鍥為€€ `SD_VULKAN/GGML_VULKAN OFF`銆乣ctx_params.backend="cpu"`銆佺Щ闄?Vulkan CMake/host 鎺ョ嚎涓庢墦鍖呮帓闄わ紱CPU 閲嶅缓 `614db669972a38e894df17861fc6d7366877a67799eb5e2f659651b3483dc7f0`锛?76,138,747B锛屾棤 libvulkan锛夛紝16:36:48 瑁?device-a锛宍device-device-a-run10` **OK (9 tests)**銆?
### 4. 缁撹涓庢仮澶嶆潯浠?
- **OpenCL + Vulkan 涓や釜鍙敤 GPU 鍚庣鍧囧湪 Wan 棣栦釜閲囨牱 matmul 鍘熺敓宕╂簝**锛圤penCL `ggml_abort`銆乂ulkan Adreno 椹卞姩 SIGSEGV锛夛紝褰撳墠 sd.cpp/Adreno 鏍堜笉鍙敤浜庤妯″瀷鍔犻€燂紱V1-06 浜у搧棰勭畻浠?**NOT_MET**銆?- 涓嬩竴姝ュ姞閫熷彧鑳借蛋 NPU/QNN锛堥渶妯″瀷杞崲涓庤嚜瀹氫箟鍚庣锛岄潪 sd.cpp 鐜版湁璺緞锛夋垨鎹㈢‖浠讹紱鍦ㄥ姞閫熷彲鐢ㄥ墠 V1 淇濇寔鏈畬鎴愩€?- 鍥為€€鐐癸細CPU `614db669鈥 宸叉仮澶嶅苟楠岃瘉锛沄ulkan 鏋勫缓浜х墿/瀹為獙 APK 涓嶈繘鍏ヤ骇鍝佺粨璁恒€?
## 鏈€缁堝洖褰掞紙2026-10-26 16:46锛孋PU 绋冲畾鏍戯級

鍙楁帶鍛戒护锛圡caBuildLock锛屽洓涓嫭绔?Gradle 璋冪敤锛宍--rerun` 鍙噸璺戞祴璇曚换鍔★級锛?
1. `:core:video-contract:testDebugUnitTest --rerun` 鈫?**45/0**锛? suites锛孊UILD SUCCESSFUL 12s锛?2. `:feature:video-workspace:testDebugUnitTest --rerun` 鈫?**29/0**锛? suites锛?2s锛?3. `:app:testDebugUnitTest --tests 'com.muyuchat.mca.video.*' --rerun` 鈫?**130/0**锛?4 suites锛?m13s锛?4. `:feature:chat:testDebugUnitTest --rerun` 鈫?**486/0**锛?4 suites锛?6s锛?
鍚堣 **690/0**锛宖resh XML 鏃堕棿 16:44鈥?6:45锛沗tools/architecture/check_architecture_rules.py` 鈫?**19/19 PASS锛圗XIT=0锛?6:39锛?*銆傛棩蹇楋細`build/manual-jvm-final-{contract,workspace,appvideo,chat}.log`銆乣build/evidence/v1-06/architecture-final.log`銆?
浠嶆湭鎵ц锛氬浘鐗?鑱婂ぉ/DSH 鐨勭湡瀹炵敓鎴愬啋鐑燂紙device-b 琚彟涓€ lane 鐨?DSH 楠岃瘉鍗犵敤锛宖9d 澶氭閿佸睆锛涗粎鏈?9 椤硅澶囨祴璇曚笌 chat JVM 486 瑕嗙洊浠ｇ爜/瀵艰埅锛夛紝浠ュ強浜у搧瑙勬牸 I2V銆佺湡瀹炶繙绔?ComfyUI銆俈1 浠嶆湭瀹屾垚锛涙渶缁?CPU APK `614db669鈥 宸茶 device-a 涓?`device-device-a-run10` 9/9銆?
## QNN/NPU 鍙鎬у璁★紙2026-10-26 17:00锛?
- 缁撹 **BLOCKED**锛歈NN SDK 2.45 鐨?`qairt-converter`/`qnn-onnx-converter`/`qnn-context-binary-generator` 涓?HTP V68鈥揤81 stub 閮藉湪锛宖9d 涓?SM8550/HTP V73銆?8a 涓?SM8750P/HTP V79锛涗絾宸ヤ綔鍖哄彧鏈?Wan2.2 **GGUF**锛屾病鏈夊師濮?safetensors/ONNX锛屼篃娌℃湁 Wan QNN context/model 鍖咃紱鐜版湁 QNN 閾捐矾鍙槸 SD1.5 鍥惧儚锛坄unet.bin`/VAE锛宑ontext ~58MB 閲忕骇锛夈€?- Wan DiT 鐨?3D conv/attention/閲忓寲 matmul 鍦?HTP 鐨?op 瑕嗙洊涓?context/鍐呭瓨涓婇檺鏈獙璇侊紱5B DiT + 4.7B T5锛圦8 ~6GB锛? VAE 鐨勮妯¤繙瓒呯幇鏈?SD1.5 QNN 鍖咃紱瑙嗛渚т篃娌℃湁 QNN execution adapter銆?- 鎭㈠鏉′欢锛氬師濮?Wan2.2 鏉冮噸鎴栧浐瀹?revision ONNX 鈫?QNN/AIMET 杞崲涓?context binaries 鈫?device-a/device-b 鐨?op/context/鍐呭瓨 spike 鈫?鍐嶆寜鐜版湁 `VideoExecutionAdapter` 杈圭晫鎺ュ叆锛堜笉鏀?owner/worker/Room/濯掍綋濂戠害锛夈€傚湪璧勪骇涓?spike 瀹屾垚鍓嶏紝QNN 淇濇寔 BLOCKED/NOT_RUN锛屼笉寰楃敤 SD1.5 QNN 鎴?CPU 缁撴灉鍐掑厖 Wan NPU銆?- 瀹屾暣瀹¤锛歚docs/proposals/mca-native-video-workflow-v1/QNN_FEASIBILITY_AUDIT.md`銆?
---

## 鎺ユ墜琛ラ綈杞紙2026-10-26 澶滐紝round-3锛夛細褰撳墠鏍戦噸寤恒€佽繘搴︿慨澶嶃€佺湡瀹?T2V/I2V/鍙栨秷/鍒犻櫎涓庤瘉鎹噸缁?
```text
trigger: 鎺ユ墜浼氳瘽鎸夆€滃彲楠岃瘉鐨勬湰鍦?V1 闂幆鈥濋€愰」澶嶈窇锛屼笉鎺ュ彈鏃ф姤鍛婃浛浠ｅ綋鍓嶈瘉鎹?start_revision: 68b6eef1e67870b02bfef6afda2e625d36663da9锛坆ranch codex/enterprise-refactor锛涙湰杞棤鎻愪氦锛?workspace: 鍏变韩宸ヤ綔鍖猴紱20:15 瀹¤鏃?676 鏉?porcelain锛圡 364 / ?? 309 / D 3锛夛紝鏈熼棿鍏朵粬 lane 缁х画鍐欏叆
submodules: llama.cpp 4ceb1719(20 鑴? / stable-diffusion.cpp 16b3846(18) / tokenizers-cpp acbdc5a(5)
devices: device-a=2304FPN6DC锛圓ndroid 16锛?5.5GB RAM锛學an 鏉冮噸宸茶锛夛紱device-b=25091RP04C锛圓ndroid 17锛岀幇瑁?cff571c5 鍊欓€夛級
toolchain: JDK21銆丼DK D:\model\android-sdk銆丵NN E:\tools\qairt-sdk\2.45.0.260326锛涘叏閮?Gradle 缁?McaBuildLock
```

### 1. 鏈疆浠ｇ爜鏀瑰姩锛堢湡瀹炵己闄风殑鏈€灏忎慨澶?+ 瀹堝崼锛?
1. **杩愯鍗¤繘搴?闃舵娈嬬暀锛堝凡淇紝宸茬湡鏈洪獙璇侊級**锛歴d.cpp 鎶婃枃鏈紪鐮?鏉′欢杩涘害涓庨噰鏍疯繘搴﹂兘鎶曞埌鍚屼竴鍥炶皟锛宯ative 鍙堟妸鎵€鏈夊洖璋冩爣鎴?`sampling`锛屼簬鏄柊浠诲姟鍦ㄥ姞杞介樁娈垫樉绀轰笂涓€浠诲姟鐨?`825/1263` 骞舵彁鍓嶈繘鍏?SAMPLING銆傛柊澧?`app/.../video/worker/VideoProgressProjection.kt`锛氫粎褰?`phase=="sampling"` 涓?`steps` 绛変簬鍐荤粨鐨勮姹傛鏁版椂鎵嶆姇褰变负 SAMPLE锛沗VideoWorkerPipeline` 浣跨敤璇ヨ繃婊ゃ€傛祴璇?`VideoProgressProjectionTest` 5/5锛?0:05 XML锛涙湰杞◢鍚庤骞跺彂 lane 瑕嗙洊锛岃 搂9锛夈€傜湡鏈?T2V 鏈疆棣栦釜 SAMPLE 涓?`0/1`锛?54s锛夛紝**鏃?825/1263銆佹棤鎻愬墠 SAMPLING**銆?2. **鐪熸満 UI 娴嬭瘯棣栧惎鏇存柊寮圭獥 flake锛堝凡淇級**锛歚VideoWorkspaceDeviceTest` 鍘熷厛鍥哄畾 8s 绐楀彛绛夆€滅◢鍚庘€濓紝寮圭獥鏅氬埌鏃?60s 鏂█澶辫触锛坮un11 澶嶇幇锛氣€渃hat did not become visible鈥濓級銆傛敼涓?90s 鏈夌晫杞锛氬悓鏃惰瀵熻亰澶╁叆鍙ｄ笌鈥滅◢鍚?杩斿洖鑱婂ぉ鈥濓紝鍑虹幇鍗冲鐞嗭紱浠嶄繚鐣欐渶缁堟柇瑷€銆?3. **V1 鐢ㄦ埛鍙鈥滈噸璇?閲嶆柊杩愯鈥濈己鍙ｏ紙鏈疆瀹氫綅锛屽凡淇級**锛歚VideoWorkflowPort.retry` 涓?owner 鐨勨€滅粓鎬佲啋鏂?attempt銆佸師缁堟€佷笉鍙樷€濇棭宸插疄鐜帮紝浣?UI/VM 浠庢湭璋冪敤锛岀粓鎬佷换鍔″崱鍙湁鍙栨秷銆佹病鏈夐噸璇曪紱鍥犳鏈疆棣栨瀹炴祴鈥滃彇娑堝悗閲嶈瘯鈥濇椂鎸夐挳涓嶅瓨鍦紙FAIL锛夈€備慨澶嶏細`VideoWorkspaceViewModel.retry(runId)` 杞彂 owner 骞堕€愬瓧鍛堢幇鎷掔粷锛沗VideoRunStatusSection` 瀵?COMPLETED/FAILED/CANCELLED/INTERRUPTED 鏄剧ず鈥滈噸鏂拌繍琛?閲嶈瘯鈥濓紙testTag `video.run_retry_action`锛夛紝澶辫触鏄剧ず `video.run_retry_error`锛涙柊澧?`VideoRetryActionContractTest` 瀹堝崼鎺ョ嚎銆傜湡鏈哄娴嬬粨鏋滆 搂7銆?4. 鏃㈡湁浠栦汉鏀瑰姩淇濈暀锛歚McaApplication.kt`/`ProcessRuntimeSettlement.kt`锛?8:45 鍏朵粬 lane锛夈€乣app/build.gradle.kts` ABI 瀹堝崼锛坉ebug 蹇呴』 arm64+x86_64锛夈€乣VideoSchemaSqlTest.kt`锛?0:42 鍏朵粬 lane 鏂板锛屽綋鍓嶇紪璇戜笉閫氳繃锛岃 搂9锛夈€傛湰杞湭瑕嗙洊/鍥炴粴杩欎簺鏀瑰姩銆?
### 2. 鏋勫缓涓庝骇鐗╄韩浠斤紙褰撳墠鏍戯紝鐪熷疄鏋勫缓锛?
- 鏋勫缓鍛戒护锛堢粡 McaBuildLock锛夛細`gradlew --no-build-cache -PmcaDeviceTestVariant=true -Pmca.abis=arm64-v8a,x86_64 :app:assembleDeviceTest :app:assembleDeviceTestAndroidTest`锛涙湰杞涓€娆＄紪璇?app 鏃惰 ABI 瀹堝崼鎷掔粷 arm64-only锛堟柊瑙勫垯锛夛紝鎸夊畧鍗敼涓哄弻 ABI銆?- 浜х墿 A锛堣繘搴︿慨澶嶅悗銆侀噸璇?UI 鍓嶏級锛歚app-deviceTest.apk` 565,556,773B @20:11:53锛宻ha256 `c5d81d3bb05aa0aa7260731791291a337fab919dcb2202f68e417b20c18b3bcf`锛泃est APK 1,643,382B @20:15:02锛宻ha256 `596a664972086b49cf44cde9347a7dd58e5a3dc03302c0538837bfc19dac0877`锛?0:11/20:15 瀹夎 device-a銆俆2V/I2V/鍙栨秷/浠櫒鍖栬瘉鎹粦瀹氳浜х墿銆?- 浜х墿 B锛堥噸璇?UI/VM + 瀹堝崼鍚庯級锛歚app-deviceTest.apk` 565,602,537B @21:38:09锛宻ha256 `9e50648a280c45b21d138d0faf738d1b218eaec1cf3c598a62709b9d70421474`锛泃est APK 1,643,494B @21:38:23锛宻ha256 `b66dfd7732b4e489be20213162a6260a769a8894ae10011c112a3a2b40425208`锛沠9d `dumpsys package` 鍘熷杈撳嚭 `lastUpdateTime=2026-10-26 21:39:08`锛堣瘉鎹?`round3b-device-a-install-time.txt`锛?1:39:32 璧?run14 OK 9锛夈€俧eature:video-workspace 鏈疆 30/0锛堝惈鏂板 `VideoRetryActionContractTest`锛夈€?- 鍘嗗彶 16:36 `614db669鈥锛?76,138,747B锛宎rm64-only锛夊凡鏃犳湰鍦板綊妗ｏ紱鍏?16:36 瀹夎涓?9/9 璁板綍淇濈暀鍦ㄦ棫鏃ュ織锛屼絾涓嶄綔涓烘湰杞瘉鎹€?
### 3. 鐪熸満浠櫒鍖栵紙缂栫爜銆佸彂甯冦€佽縼绉汇€丣NI銆乁I锛沠9d锛岀湡瀹炶澶囷級

| 杞 | 浜х墿/娴嬭瘯 APK | 鍛戒护 | 缁撴灉 |
| --- | --- | --- | --- |
| run11 | 614db669 + 宸茶 test APK 848719bd | `am instrument -w -r -e class` 5 绫伙紙9 tests锛?| **FAIL 1/9**锛歚VideoWorkspaceDeviceTest.drawerEntry鈥 鍦?44 琛?鈥渃hat did not become visible鈥濄€傜枒浼兼洿鏂板脊绐楁櫄浜庡浐瀹?8s 绐楀彛锛堝悗缁喎鍚姩澶嶇幇寮圭獥鍐嶆鍑虹幇锛夛紝浣嗗綋鏃跺け璐ユ€佹埅鍥惧洜 PowerShell 閲嶅畾鍚戞崯鍧忋€侀潪鏈夋晥 PNG锛屾晠鍥犳灉璁颁负**鐤戜技**鑰岄潪宸茶瘉锛涙祴璇曞凡鏀逛负 90s 鏈夌晫杞 |
| run12 | 鍚屼笂锛堟崲瑁呭綋鍓?test APK 鍚庯級 | 鍚屼笂 | **OK (9 tests)**锛?.884s |
| run13 | **c5d81d3b** + test APK 596a6649 | 鍚屼笂 | **OK (9 tests)**锛?2.224s |
| run14 | **浜х墿 B 9e50648a** + test APK b66dfd77 | 鍚屼笂 | **OK (9 tests)** |

- run11 鐨?FAIL 淇濈暀锛況un12/13 鐨勯€氳繃涓嶅悎骞?run11銆傞杞?25091RP04C 3 PASS/6 FAIL 鍘嗗彶缁撴灉淇濇寔涓嶅彉锛堣瘉鎹湪 `%TEMP%\mca-video-device3.log`@03:08锛宍build/` 鏃犺鎵规鍓湰锛夈€?- 鍘熷鏃ュ織锛歚build/device-device-a-run11-instrument-20261026.log`銆乣run12`銆乣run13`銆?
### 4. 鐪熷疄妯″瀷鎵ц锛坒9d锛屼骇鐗?A锛岀湡瀹?Wan2.2 TI2V-5B Q4_0+Q8_0+Wan2.2 VAE锛?
| 鍔ㄤ綔 | run / 缁撴灉 | 瀹炴祴 |
| --- | --- | --- |
| T2V 64脳64脳9脳1 | `v-fb254af5鈥 **COMPLETED/RELEASED** | 228s锛坉river 鏈閲囨牱锛? 229s锛圝SON elapsed锛夛紱宄板€?worker PSS 7,963,708KB锛涚數姹?37.0掳C / zone0 宄板€?46.98掳C锛涢噰鏍?0/1鈫?/1锛?*杩涘害鏃?825/1263 寮傚父** |
| T2V MP4 | `media-73793c85鈥 | 7,209B锛汳ediaExtractor 9 甯?64脳64/16fps/0.5625s/H.264/鏃犻煶杞紱DB sha=鎷夊彇 sha=`1209881e7d5b23019f817ba6c8f7523ede24023ac0d11953285d8860e1dec276`锛涢€愬抚鍧囧€?23.4鈥?3.7銆佸抚闂村樊 1.6鈥?8.7锛堥潪绌轰笖鍙樺寲锛?|
| I2V 64脳64脳9脳1 | `v-8be671ea鈥 **COMPLETED/RELEASED** | 247s锛坉river锛? 249s锛圝SON锛夛紱宄板€?PSS 8,677,963KB锛涚數姹?38.0掳C / zone0 47.24掳C锛泂napshot `imageRef=true`锛堢湡瀹?content URI 缁戝畾锛?|
| I2V MP4 | `media-dce62541鈥 | 2,400B锛? 甯?64脳64/16fps锛汥B sha=鎷夊彇 sha=`c5defe01be57ec194f9f0b36c4cbd6979669e438544a02d45bbe83c36604172f` |
| 鍙栨秷锛? 姝ワ級 | `v-a74a64d5鈥 **CANCELLED/RELEASED** | 鎻愪氦鍚?200s 杩涘叆 SAMPLING(0/4)锛岀偣鈥滃彇娑堜换鍔♀€濆悗椹卞姩瑙傚療 **6s** 缁撶畻涓?CANCELLED/RELEASED锛圖B 浜嬩欢闂撮殧 1.57s锛夛紝閿欒鐮?`CANCELLED_AFTER_NATIVE_STOP` |
| 杩涚▼姝讳骸鎭㈠ | `v-ca3e1e17鈥 **INTERRUPTED/UNCONFIRMED** | 澶栭儴浼氳瘽鍦?CONDITIONING 寮烘潃 app锛涢噸杩涜棰戦〉鍚?owner 瀵硅处涓?INTERRUPTED锛岄敊璇爜 `INTERRUPTED_PROCESS_DEATH`锛屾槑缁嗏€滆繘绋嬮噸鍚椂浠诲姟浠嶅湪 CONDITIONING锛屽凡鎸変腑鏂粨绠椻€濓紱worker 杩涚▼涓嶅瓨鍦?|
| 閲嶅杩愯 | 鍚岃繘绋?璺ㄨ繘绋嬪悇涓€娆＄湡瀹炴彁浜わ紙T2V鈫扞2V鈫? 姝ュ彇娑堬級 | 涓?run 鍧?COMPLETED/CANCELLED 涓?RELEASED锛屾湭鍑虹幇绗簩 runtime 鎴栨畫鐣?worker |

- **璐ㄩ噺璇氬疄杈圭晫锛堟湰杞柊鍙戠幇锛?*锛氭渶灏忚鏍?T2V 甯ф槸钃濈传鑹叉娊璞＄汗鐞嗭紙闈炩€滅孩鐞?鏈ㄦ鈥濊涔夛級锛孖2V 杈撳叆鏈韩鏄?2136脳3200 鐨勭櫧鑹叉埅鍥撅紙鍧囧€?RGB鈮?44/246/247锛夛紝杈撳嚭甯ц繎鐧斤紙鍧囧€?245鈥?54銆佸抚闂村樊 0.4鈥?.6锛夈€傚洜姝も€淢P4/甯у畬鏁存€с€佹祦绋嬫纭€р€漃ASS锛屼絾 **64脳64脳9脳1 涓€姝ヤ笉灞炰簬鈥滅湡瀹炲彲杈ㄨ瘑瑙嗛鈥?*锛涙棫浜ゆ帴鎶?I2V 鏍?PASS 浠呰鐩栧獟浣?娴佺▼锛屼笉瑕嗙洊璇箟璐ㄩ噺銆俈1 瀹屾垚瀹氫箟涓殑鈥滃彲杈ㄨ瘑鐭墖娈碘€濅粛 **NOT_MET**銆?- 鍘熷璇佹嵁锛歚round3-i2v-{driver.log,result.json}`, `round3-i2v.mp4`, `round3-i2v-frames/`, `round3-cancel-driver.log`, `round3-db/`銆俆2V锛堜骇鐗?A锛夌殑 driver/result/frames 鏂囦欢鍦?round-4 澶嶇敤鍚屽悕 harness 鏃惰瑕嗙洊锛岄殢鍚庢敼鍚嶄负 `round4-t2v-artifactC-*`锛汚 鐨?MP4 瀛楄妭浠嶇敱 `round3-exported-203000.mp4`锛?,209B锛宻ha `1209881e鈥锛変繚鐣欙紝A 鐨?run/浜嬩欢鍦?`round3b-db-summary.txt`锛孉 鐨勯┍鍔ㄨ娴嬪€艰褰曞湪鏈妭琛ㄤ腑锛涜鐩栦簨瀹炰笌杈圭晫瑙?`round4-t2v-README.txt`銆?
### 5. 濯掍綋鍔ㄤ綔锛坒9d锛屼骇鐗?A锛涚湡瀹?64脳64 鎴愮墖锛?
| 鍔ㄤ綔 | 缁撴灉 | 璇佹嵁 |
| --- | --- | --- |
| 鎾斁 | PASS锛氫綔鍝佸簱鈫掓挱鏀惧櫒鎵撳紑锛?4脳64/16fps/鏃堕暱淇℃伅锛屾挱鏀惧悗杩涘害鏉℃帹杩涘埌灏鹃儴锛屽叧闂挱鏀惧櫒鍥炲埌浣滃搧搴?| `round3-player2.png`銆乣round3-player-playing.png` |
| 鍒嗕韩 | PASS锛歁IUI 绯荤粺鍒嗕韩闈㈡澘锛堝皬绫冲垎浜?QQ/钃濈墮/鐭俊锛?| `round3-share-sheet.png` |
| 鍙﹀瓨 | PASS锛氱郴缁?DocumentsUI 淇濆瓨鍒?`/sdcard/Download/MCA_video_20261026_203000.mp4`锛?209B锛涗簩杩涘埗瀹夊叏鎷夊洖鍚?sha256 涓庡師鐗囦竴鑷?`1209881e鈥 | `round3-save-state.png`銆乣round3-exported-203000.mp4` |
| 鍒犻櫎 | PASS锛堝垹闄ゅ崗璁敓鏁堬紱璁℃暟鍒锋柊涓€娆℃粸鍚庯級锛氱‘璁ゅ脊绐椻啋tombstone+unlink锛涙渶缁?`video_media_assets` 4 琛屻€乣files/video/media` **璁惧绔?`ls` 4 鏂囦欢**锛堣瘉鎹?`round3b-media-files-after-delete.txt`锛夛紝涓や釜 round-3 鏈€灏忚鏍肩鏈夊壇鏈紙T2V `media-73793c85鈥銆両2V `media-dce62541鈥锛夊潎宸茬Щ闄わ紱宸插鍑?T2V 鍓湰浠嶅湪 Download銆俇I 璁℃暟鍦ㄧ浜屾鍒犻櫎鍚庢樉绀?4锛涚涓€娆″垹闄ゅ悗璁℃暟鍒锋柊婊炲悗涓€娆★紝涓嶆嵁姝ゆ柇瑷€鈥滃崟娆?5鈫?鈥?| `round3-after-delete2.png`銆乣round3-delete-test4.png`銆乣round3-db/after-delete-*/`銆乣round3b-db-summary.txt`銆乣round3b-media-files-after-delete.txt` |
| 鍒犻櫎鑷姩鍖栧娉?| 鏈疆 UI 鑴氭湰澶氭鍛戒腑閿欒 y 鍧愭爣瀵艰嚧鈥滄棤寮圭獥/鏃犳晥鏋溾€濓紱浠ヨ涔夋爲 clickable View 鐨?bounds 绮剧‘鐐瑰嚮鍚庝竴娆℃垚鍔熴€備笉鏄骇鍝佺己闄?| 瑙佷笂 |

### 6. 瑙嗚涓庢棤闅滅锛?8a锛屽綋鍓嶅畨瑁呬骇鐗?cff571c5锛岄潪鏈疆 APK锛?
- 鎶藉眽椤哄簭 鍥剧墖鈫扐gent鈫扗SH鈫掕棰戔啋鏈€杩?PASS锛涗笁鍏ュ彛杩涘叆/杩斿洖 PASS锛涜棰戦〉 HOME鈫掑洖鍓嶅彴鐘舵€佷繚鐣?PASS锛涙祬鑹?娣辫壊锛堜寒搴﹀潎鍊?246.1鈫?6.1锛?鎭㈠娴呰壊 PASS锛汻oom `user_version=32`锛寁ideo_* 4 琛ㄥ瓨鍦紙revisions=1/runs=1/events=3/media=0锛夛紝鏃ц〃琛屾暟淇濈暀 PASS銆?- 涓よ疆 QA 鍙戠幇锛圧2 澶嶆牳鍓?4 椤逛粛瀛樺湪锛夛細绔彛瑙︾偣 5/8 楂?21.8鈥?2.2dp锛?48dp锛夛紱绔彛 desc 缂鸿妭鐐瑰悕涓?class=View锛圧ole.Button 璇箟鏈綋鐜帮級锛涜妭鐐瑰垪琛?11 涓?clickable 瀹瑰櫒鏃犺嚜韬爣绛撅紱鑺傜偣琛屾彁绀鸿瘝鎽樿鎴柇锛涙娊灞夆€滃浘鐗?鏈€杩戔€濇棤 content-desc銆?- 闀挎彁绀鸿瘝浠呰嫳鏂?180 瀛楃鎴愬姛锛涗腑鏂囨敞鍏ュ彈杈撳叆娉?adb 闄愬埗锛屾爣 RESTRICTED銆傝亰澶╁啋鐑?NOT_RUN锛堟棤宸插姞杞芥ā鍨嬶級銆傛姤鍛婏細`build/evidence/v1-06/round3-device-b-report.md`锛涜瘉鎹墠缂€ `round3-device-b-*`銆?- 娉ㄦ剰锛?8a 涓?device-a 鍧囦负鍏变韩璁惧锛屾湡闂存湁鍏朵粬 lane 鐨?Local API/DSH 鎿嶄綔锛泀a 宸叉妸鍙楀共鎵伴」鏍?RESTRICTED锛屾湭鐢ㄧ灛鏃跺浘涓嬬粨璁恒€?
### 7. 閲嶈瘯缂哄彛涓庡娴嬶紙浜х墿 B锛?
- 浜х墿 A 瀹炴祴锛氬彇娑堝悗鐨勭粓鎬佸崱鐗囨病鏈夆€滈噸璇曗€濇寜閽紙`閲嶈瘯` 浠呭瓨鍦ㄤ簬杩愯璁板綍璇诲彇澶辫触鍜屾挱鏀惧櫒閿欒鎬侊級銆傝繖鏄?V1 瑕佹眰鐨勭敤鎴峰彲瑙佺己鍙ｏ紝鍒?**FAIL**銆?- 宸插疄鐜?UI/VM 鎺ョ嚎锛埪?.3锛夊苟鏂板濂戠害娴嬭瘯锛坒eature 30/0锛夈€?- 浜х墿 B 鐪熸満澶嶆祴 PASS锛氬湪 CANCELLED 缁堟€佸崱锛坄v-a74a64d5鈥锛宺ev `rev-083cddc97b9a371d`锛夌偣鈥滈噸璇曗€濃啋 鏂?run `v-11e71c81-a560-43df-b67a-f5fdc5454072` / 鏂?attempt `v-d4205a44-9290-4917-a92c-7ca99de7f2da`锛宺evision 浠嶄负 `rev-083cddc97b9a371d`锛涘師缁堟€?run 鏈鏀瑰啓锛堢粓鎬?閿欒鐮?鏃堕棿鎴充笉鍙橈級銆?- 澶嶆祴涓鏂?run 鍐嶇偣鈥滃彇娑堜换鍔♀€濓紙姝ゆ椂闃舵 TEXT_ENCODE锛夛細绔嬪嵆杩涘叆 CANCEL_REQUESTED/STOPPING锛?*149.04s** 鍚?CANCELLED/RELEASED锛岄敊璇爜 `CANCELLED_AFTER_NATIVE_STOP`锛圖B 浜嬩欢 atMs锛欳ANCEL_REQUESTED 1793022061178 鈫?CANCELLED 1793022210220锛夈€傜粨璁猴細閲嶈瘯鍏ュ彛涓?owner 璇箟 PASS锛涘彇娑堝湪妯″瀷鍔犺浇/鏂囨湰缂栫爜闃舵涓嶈兘鍗虫椂纭锛屽欢杩熷睘宸茬煡涓嶅彲涓柇闃舵锛堥噰鏍烽樁娈靛彇娑堬細椹卞姩瑙傚療 6s / DB 浜嬩欢 1.57s锛夈€傝瘉鎹細`round3b-retry-page`/`round3b-retry-started`/`round3b-retry-cancel-*` 鎴浘銆乣round3b-retry-cancel-monitor.log`銆乣round3b-db-summary.txt` 涓?DB events銆?
### 8. JVM 涓庢灦鏋勯棬绂侊紙褰撳墠鏍戯紝鐪熷疄鎵ц锛?
| 鑼冨洿 | 鍛戒护 | 缁撴灉 |
| --- | --- | --- |
| 鏋舵瀯 | `python tools/architecture/check_architecture_rules.py` | **19/19 PASS锛圗XIT=0锛?1:0x fresh锛?* `round3-arch.log` |
| core:video-contract | `:core:video-contract:testDebugUnitTest --rerun` | **45/0 PASS** |
| feature:video-workspace | `:feature:video-workspace:testDebugUnitTest --rerun` | **30/0 PASS**锛堜骇鐗?B 鎵规锛屽惈 `VideoRetryActionContractTest`锛?9/0 涓哄姞瀹堝崼鍓嶆壒娆★級 |
| feature:chat | `:feature:chat:testDebugUnitTest --rerun` | **486/0 PASS**锛堝惈 ChatWorkspaceNavigationTest 2/0锛?|
| app 瑙嗛杩囨护 | `:app:testDebugUnitTest --tests 'com.muyuchat.mca.video.*' --tests 'com.muyuchat.mca.UniversalDeviceAdmissionPolicyTest' --rerun`锛坮ound-2 楠岃瘉鍏ュ彛锛?| **PASS锛?40/0锛?6 suites锛?*锛?35 涓棰戞祴璇曪紙鍚?`VideoProgressProjectionTest` 5/0锛? policy 5/0锛孹ML mtime 2026-10-26 22:17鈥?2:2x锛屽綊妗?`round3-xml/app-video-round4/`銆傛柟娉曡鏄庯細骞跺彂 lane 鐨勬湭璺熻釜 `app/src/test/.../video/data/VideoSchemaSqlTest.kt` 浠嶄笉鍙紪璇戜笖涓嶅緱鏀瑰姩锛涙湰浼氳瘽鐢?*浠呮湰娆¤皟鐢ㄧ敓鏁堢殑 Gradle init script**锛坄build/round4-apptest-init.gradle`锛夋妸璇ユ枃浠朵粠 `compileDebugUnitTestKotlin` 杈撳叆涓帓闄わ紝鏈紪杈?鍒犻櫎璇ユ枃浠躲€傝鏂囦欢鑷韩鐨?2 涓祴璇曚粛 **NOT_RUN**锛堜粠鏈紪璇戯級锛涜嫢鍚庣画瑕佹眰鍏ㄩ噺鍚畠锛屾仮澶嶆潯浠?璇?lane 淇鍏?`JdbcSupportSQLiteDatabase`/`Connection` 绫诲瀷閿欒銆傚巻鍙?incidental锛氬苟鍙?lane 鐨?21:55 鍏ㄩ噺璺?15 瑙嗛 suite=135/0锛坄round3-xml/app-video-incidental-2155/`锛変粎浣滄梺璇?|
| 鍏ㄩ噺鍘嗗彶 690 | 16:44鈥?6:45 XML | **STALE/宸茬伃澶?*锛歛pp video 130/0 鐨?14 浠?XML 琚苟鍙?lane 瑕嗙洊锛涘綋鍓嶆湰浼氳瘽鍙牳 45+30+486=**561**锛坅pp 135 鍙﹁锛屾湰浼氳瘽杩囨护璺戣闃绘柇锛夛紱鏈疆涓嶅紩鐢ㄦ棫 690 浣滀负褰撳墠鏍戦€氳繃璇佹槑 |

- 璇佹嵁锛歚build/evidence/v1-06/round3-arch.log`銆乣round3-jvm-{contract,workspace,chat,appvideo}.log`銆乣round3-xml/`銆?
### 9. 妯″瀷璧勪骇涓庤繙绔?
- device-a 璁惧渚?sha256 瀹炵畻涓夋枃浠朵笌 pin 瀹屽叏涓€鑷达細DiT Q4_0 `fcf40dd6鈥(3,029,086,560B)銆乁MT5 Q8_0 `2521d4de鈥(6,043,068,256B)銆乂AE `e40321bd鈥(1,409,400,960B)锛涘師濮嬭緭鍑?`model-hash-device-a-20261026.txt`銆?- HF API 澶嶆牳锛?026-10-26锛夛細Wan-AI 鍩虹浠撳簱 `921dbaf3鈥 Apache-2.0锛決uantStack `57437632鈥 Apache-2.0锛圦4_0/Q4_K_M/Q8_0 涓?VAE 鐨?size/lfs.oid 涓€鑷达級锛沜ity96 `b535255b鈥 Apache-2.0锛圲MT5 Q8_0/Q4_K_M 涓€鑷达級锛汣omfy-Org/Wan-2.2 `ee6f4a40鈥 Apache-2.0锛宖p16 sha `456f9013鈥锛堟棫鍚?`Wan_2.2_ComfyUI_Repackaged` 宸查噸鍛藉悕锛夈€傚閫夋潈閲嶆湭涓嬭浇/鏈繍琛?NOT_RUN銆?- 杩滅▼ ComfyUI锛氭湰鏈?8188/8189/8000/3000/5000/8888 绛夋棤鐩戝惉銆佹棤 comfy 杩涚▼/瀹夎銆佹棤 endpoint/鍑嵁锛汱AN 鍙閭诲眳浜︽棤銆傚浐瀹?revision endpoint 涓嶅瓨鍦?鈫?鐪熷疄 submit/WS/history/queue/download/cancel **BLOCKED**锛屾湭浼€犳彁浜ゃ€傛仮澶嶆潯浠惰 `round3-remote-assets.md`銆?- 鍔犻€燂細OpenCL/Vulkan 浠?FAIL锛涙湰杞彧璇荤爺绌剁‘璁?Wan 浣跨敤 `stable-diffusion.cpp/ggml` 鐨?leejet fork pin锛堢己涓婃父 Adreno tile 淇 `d14d9db8c`锛夛紝鎻愬嚭绾?0.5 浜烘棩鐨?Vulkan 閲嶅缓 spike锛涙湭鎵ц锛屼笉鏀瑰彉浜у搧棰勭畻缁撹銆俀NN 鍏ㄦā鍨嬩粛涓嶅彲鎵ц銆傛姤鍛?`round3-accel-research.md`銆?
### 10. 骞跺彂涓庤澶囧共鎵帮紙濡傚疄璁板綍锛?
- device-a锛?0:40 涓?21:04 鍓嶅悗鍑虹幇闈炴湰浼氳瘽鍙戣捣鐨?instrumentation/force-stop 涓?`run-as ... cp /data/local/tmp/mca-restore.db databases/mca.db`锛坙ogcat 20:44:20锛夛紝瀵艰嚧 I2V 棣栬疆鍦?CONDITIONING 琚潃锛涜涓柇琚敤浣滆繘绋嬫浜℃仮澶嶈瘉鎹紙INTERRUPTED/UNCONFIRMED锛夈€?- 鏋勫缓閿侊細20:42 浠栦汉 `verify-code.ps1` 鍗犵敤锛?1:18 璧蜂粬浜?鈥淒SH completion-v2 R2 freeze + gates鈥?闀挎椂闂村崰鐢紝鏈疆浜х墿 B 绛夊緟閿併€?- 鏈疆鏈彁浜ゃ€佹湭鎺ㄩ€併€佹湭鍙戝竷锛沗AGENTS.md` 涓?`UniversalDeviceAdmissionPolicyTest.kt` 浠嶅湪 `.git/info/exclude`锛堝璁?PASS锛夈€?
### 11. 鏈疆鐭╅樀锛堝瀹烇級

| 椤?| 鐘舵€?| 渚濇嵁 / 缂哄彛 |
| --- | --- | --- |
| 缂栫爜/鍙戝竷/杩佺Щ/JNI/UI 璁惧浠櫒鍖?| **PASS**锛坮un13锛屼骇鐗?A锛?| OK(9 tests)锛況un11 1/9 鏇存柊寮圭獥 flake 淇濈暀骞跺凡淇?|
| 鐪熷疄 T2V 鏈€灏忚鏍煎嚭鐗?| **PASS锛堟祦绋?濯掍綋锛?* | 228鈥?29s/7.96GB/MP4 9 甯?sha 1209881e鈥?|
| 鐪熷疄 I2V 鏈€灏忚鏍煎嚭鐗?| **PASS锛堟祦绋?濯掍綋锛夛紱璇箟璐ㄩ噺 NOT_MET** | 247鈥?49s/8.68GB/imageRef 缁戝畾锛涘抚杩戠櫧涓旇緭鍏ヤ负鐧借壊鎴浘锛屼笉鍙О鍙鲸璇?|
| 鍙栨秷/閲婃斁 | **PASS** | 閲囨牱闃舵鍙栨秷锛氶┍鍔ㄨ瀵?6s / DB 浜嬩欢 1.57s锛汿EXT_ENCODE 闃舵鍙栨秷 149.04s锛圖B 浜嬩欢宸級鍚庣‘璁わ紙涓嶅彲涓柇闃舵锛屽瀹炶褰曪級锛涘潎 CANCELLED/RELEASED + CANCELLED_AFTER_NATIVE_STOP |
| 杩涚▼姝讳骸鎭㈠ | **PASS锛堣瀵燂紝澶栭儴寮烘潃锛?* | INTERRUPTED/UNCONFIRMED锛孖NTERRUPTED_PROCESS_DEATH |
| 鎾斁/鍒嗕韩/瀵煎嚭/鍒犻櫎 | **PASS** | 鎾斁鍣ㄨ繘搴︽帹杩涳紱鍒嗕韩闈㈡澘锛涘鍑哄瓧鑺備竴鑷达紱鍒犻櫎鍗忚绉婚櫎绉佹湁琛?鏂囦欢锛堟渶缁?DB 4 琛?鐩綍 4 鏂囦欢锛夛紝瀵煎嚭淇濈暀锛涜鏁板埛鏂颁竴娆℃粸鍚庡凡娉ㄦ槑 |
| 閲嶈瘯/閲嶆柊杩愯 | **PASS锛堜骇鐗?B锛?* | 缁堟€佸崱鈫掓柊 run/attempt 缁戝畾鍚屼竴 revision锛屽師缁堟€佷笉鍙橈紱绔埌绔娴嬭 搂7 |
| 璁惧浠櫒鍖栵紙浜х墿 B锛?| **PASS** | run14 OK(9 tests)锛況un13 涓轰骇鐗?A |
| 浜у搧瑙勬牸 480脳832脳33 | **NOT_MET锛堝巻鍙插疄娴嬶級** | 1 姝?>20min 琚?watchdog 缁堟锛?0 姝ュ皬鏃剁骇锛涘姞閫?FAIL/BLOCKED |
| V1-06 棰勭畻鍐荤粨骞舵弧瓒?| **NOT_MET** | 鍙瓨鍦?64脳64 瀹為獙瑙勬牸锛涗笉婊¤冻鈥滀骇鍝佸彲鐢ㄢ€?|
| 瑙嗚/鏃犻殰纰嶏紙device-b锛?| **PASS锛堝瓙闆嗭級+ 宸茬煡缂洪櫡** | 娴?娣辫壊銆侀敭鐩樸€侀暱鏂囨湰銆佷袱杞?QA锛涚鍙?22dp 绛?4 椤规湭淇?|
| 鑱婂ぉ/鍥剧墖 native 璧勬簮浠茶 | **NOT_RUN锛堟湰杞級** | device-b/device-a 鍧囨棤鍥剧墖妯″瀷锛涗粎鏈?JVM 閿佸苟鍙戞祴璇曞巻鍙诧紱涓嶅緱鍐欐垚 PASS |
| Room v29鈫?2 杩佺Щ | **PASS锛堣澶囦华鍣ㄥ寲锛屼骇鐗?A锛?* | VideoRoomMigrationTest 鍦?run13 9/9 鍐咃紱JVM SQL 杩佺Щ娴嬭瘯琚粬浜哄崐鎴愬搧闃绘柇 |
| 鑱婂ぉ/DSH 鐪熷疄鍐掔儫 | **NOT_RUN** | device-b 鏃犲凡鍔犺浇妯″瀷锛涘閮?lane 骞跺彂鍗犵敤 |
| 杩滅 ComfyUI | **BLOCKED** | 鏃犲浐瀹?revision endpoint |
| V1 瀹屾垚鍒ゅ畾 | **鏈畬鎴?* | 浜у搧棰勭畻銆佸彲杈ㄨ瘑璐ㄩ噺銆侀噸璇曞娴嬶紙浜х墿 B锛変笌浠茶/鍥炲綊缂哄彛鏈棴鍚?|

### 12. 鍥為€€鐐?
- 鏈疆浠ｇ爜锛氬垹闄?`VideoProgressProjection.kt`+娴嬭瘯骞惰繕鍘?`VideoWorkerPipeline` 杩涘害杩囨护锛涜繕鍘?`VideoWorkspaceDeviceTest` 鐨勫脊绐楄疆璇紱杩樺師 `VideoWorkspaceViewModel`/`VideoRunStatusSection` 鐨勯噸璇曟帴绾垮苟鍒犻櫎 `VideoRetryActionContractTest`銆?- 浜х墿 A/B 浠呭畨瑁呭埌 device-a锛坉ebug 绛惧悕 devicetest 鍖咃級锛沺roduction `com.muyuchat.mca` 鏈姩锛屾ā鍨嬩笌鍘嗗彶鍦?app 绉佹湁鐩綍銆傛湭鎻愪氦/鏈帹閫併€?```

---

## 鎺ユ墜琛ラ綈杞?2锛?026-10-26 娣卞锛宺ound-4锛夛細JVM 闂ㄧ琛ラ綈銆佹棤闅滅淇涓庝骇鐗?C銆佸浘鐗囧紩鎿庡鍏?浠茶灏濊瘯

```text
trigger: 缁х画琛ラ綈 V1 闃诲椤癸紱鍏堣ˉ褰撳墠鏍戝彲鐙珛鎵ц鐨?JVM 闂ㄧ锛屽啀淇?device-b 涓よ疆 QA 鐨勬棤闅滅缂哄彛锛屾渶鍚庡皾璇曠湡瀹炲浘鐗囦换鍔′互楠岃瘉 image脳video native 浠茶
start_revision: 68b6eef1e67870b02bfef6afda2e625d36663da9锛宐ranch codex/enterprise-refactor锛涙湰杞棤鎻愪氦
devices: device-a锛堜骇鐗?B/C锛夛紱device-b 琚叾浠?lane 鏇存崲涓?4ef1a121 鏋勫缓骞跺崰鐢?DSH/chat
toolchain: 鍏ㄩ儴 Gradle 缁?McaBuildLock锛汚PK 鍧囦负鍙?ABI锛坅rm64-v8a,x86_64锛塪eviceTest 鍙樹綋
```

### 1. 褰撳墠鏍?JVM 闂ㄧ锛堟湰浼氳瘽鐙珛鎵ц锛?
- **app 瑙嗛 + policy锛歅ASS 140/0锛?6 suites锛?*锛?2:17 fresh XML锛屽惈 `VideoProgressProjectionTest` 5/0 涓?`UniversalDeviceAdmissionPolicyTest` 5/0銆傛柟娉曪細骞跺彂 lane 鐨勬湭璺熻釜 `app/src/test/.../video/data/VideoSchemaSqlTest.kt` 浠嶄笉鍙紪璇戜笖涓嶅緱缂栬緫锛涙湰浼氳瘽浣跨敤**浠呮湰娆¤皟鐢ㄧ敓鏁?*鐨?Gradle init script `build/round4-apptest-init.gradle`锛屽湪 `compileDebugUnitTestKotlin` 杈撳叆涓帓闄よ鍗曟枃浠讹紙`projectsEvaluated` 鍚庤缃?fileTree锛夛紝鏈慨鏀?鍒犻櫎璇ユ枃浠躲€傝鏂囦欢鑷韩 2 涓祴璇曚粛 **NOT_RUN**锛堜粠鏈垚鍔熺紪璇戯級锛涙仮澶嶆潯浠?璇?lane 淇ソ `JdbcSupportSQLiteDatabase`/`Connection` 绫诲瀷閿欒鍚庡叏閲忛噸璺戙€?- **feature:video-workspace锛歅ASS 32/0**锛?0 + retry 瀹堝崼 1 + 鏈疆鏃犻殰纰嶅畧鍗?2锛夛紝`round4b-feature2.log`銆乣round3-xml/workspace-a11y/`銆?- 璇佹嵁锛歚round4-app-jvm.log`銆乣round3-xml/app-video-round4/`锛?6 XML锛屽疄绠?140/0锛夈€乣round4b-feature2.log`銆乣round4b-assemble.log`銆?
### 2. 鏃犻殰纰嶄慨澶嶄笌浜х墿 C锛圴1-06 涓よ疆 QA 缂哄彛锛?
鏀瑰姩锛堝潎浠呰涔?鏍囩锛屼笉鏀瑰竷灞€涓庤瑙夛級锛?- `VideoGraphCanvas`锛氱鍙ｈ涔変粠鈥滆緭鍑虹鍙?prompt鈥濇敼涓衡€?*鏂囧瓧杈撳叆 杈撳嚭绔彛 prompt**鈥濓紙杈撳叆绔彛鍚岀悊甯﹁妭鐐瑰悕锛夛紱鑺傜偣鍒楄〃鐨勨€滃弬鏁?/ 浠?x 杩炲嚭 / 鐩爣杈撳叆绔彛鈥濇寜閽姞鏄惧紡 `contentDescription`锛堝惈鑺傜偣鍚嶏級銆?- `ChatHistoryDrawerScreens`锛氭娊灞夆€滃浘鐗団€濆叆鍙ｈˉ `testTag = "drawer.images"` 涓?`accessibilityLabel = "鎵撳紑鍥剧墖宸ヤ綔鍖?`锛屼笌 Agent/DSH/瑙嗛涓€鑷淬€?- 鏂板 `VideoAccessibilityLabelsContractTest`锛? tests锛夊畧鍗笂杩版爣绛俱€?
浜х墿 C锛歚app-deviceTest.apk` 568,213,041B @22:29:29锛宻ha256 `e337ba0ef33acff2c7e3be9ae0422a49907fb88d8cf9f6a906ec94af707ef1da`锛泃est APK 浠?`b66dfd77鈥锛沠9d lastUpdateTime **22:31:06**锛況un15 `OK (9 tests)`锛坄round4-run15-instrument.log`锛夈€?
璁惧楠岃瘉锛堜骇鐗?C锛宖9d锛夛細
- 鐢诲竷绔彛璇箟锛歚鏂囧瓧杈撳叆 杈撳嚭绔彛 prompt`銆乣鏂囧瓧杈撳叆 杈撳嚭绔彛 negative_prompt`锛坈lickable=true锛岃妭鐐瑰悕宸茶繘鍏ョ偣鍑昏妭鐐癸級銆傝瘉鎹?`round4-canvas.xml`銆?- 鑺傜偣鍒楄〃 sheet锛氬嚭鐜?`鏂囧瓧杈撳叆 鍙傛暟`銆乣鏂囧瓧杈撳叆 浠?prompt 杩炲嚭`銆乣鏉′欢鏋勫缓 鍙傛暟` 绛夋樉寮?desc锛泆iautomator 鍘熷鏍戜腑鈥滄棤 text/desc 鐨?clickable 瀹瑰櫒鈥濈敱 11 闄嶅埌 9锛屽墿浣?9 涓槸 Material 鎸夐挳鐨?clickable 鍖呰鑺傜偣锛屾爣绛句互鐩搁偦 semantics 鑺傜偣瀛樺湪銆俆alkBack 瀹為檯鍚堝苟鏈楄**鏈嚜鍔ㄥ寲楠岃瘉**锛堟棤 TalkBack 椹卞姩锛夛紝淇濇寔 NOT_VERIFIED銆傝瘉鎹?`round4-node-sheet.xml`銆?- 鎶藉眽锛氳涔夋爲鍑虹幇 `鎵撳紑鍥剧墖宸ヤ綔鍖篳銆傝瘉鎹?`round4-drawer.xml`銆乣round4-drawer.png`銆?- 鏈慨锛氱鍙ｅ渾鐐逛粛 22dp锛?48dp锛夛紱鑺傜偣琛屾彁绀鸿瘝鎽樿鎴柇锛涚鍙ｈ涔?class 浠嶄负 View锛圕ompose 璇箟鍥烘湁锛夈€俆alkBack 瀹炶鏈獙璇併€?
### 3. 鐪熷疄鍥剧墖寮曟搸瀵煎叆涓?image脳video 浠茶灏濊瘯锛堝叧閿礋缁撴灉锛?
- **瀵煎叆 PASS**锛氬湪 device-a 涓婁粠鐢熶骇鍖?`anima-preview` 鐢熸垚 5,628,160,827B zip锛圖: 鏆傚瓨锛夊苟 push 鍒?`/sdcard/Download/anima-preview.zip`锛岀粡**鐢熶骇 UI**锛堝浘鐗団啋妯″瀷绠＄悊鈫掔敓鍥?0)鈫掆€? 瀵煎叆鏈湴鐢熷浘寮曟搸鍖呪€濓級瀵煎叆鎴愬姛锛歎I 鏄剧ず鈥滃凡瀵煎叆鏈湴鍥惧儚鐢熸垚寮曟搸锛欰nima Preview (user-imported, CPU)鈥濓紝鐢熷浘(1)锛?.24GB锛孉nima / stable-diffusion.cpp / CPU 鐢熷浘銆傝瘉鎹?`round4-image-import-monitor.log`銆乣round4-import-state.png`銆?- **鐪熷疄鍥剧墖浠诲姟鏈惎鍔紙BLOCKED锛?*锛氬浘鐗囬〉閫夋嫨 Anima銆佹枃鐢熷浘銆?56脳256銆佹鏁?2銆佹彁绀鸿瘝 `a red apple on a woden table`锛涚偣鍑荤敓浜р€滅敓鎴愬浘鐗団€滷AB锛坈lickable View [1191,2811][1359,2979]锛夊悗鏃犱换浣曚换鍔＄姸鎬?杩涘害/閿欒锛宍:local_image` 杩涚▼涓嶅瓨鍦紝`image_assets` 浠嶄负 0锛屼富杩涚▼鏃?image 鐩稿叧鏃ュ織銆傞《灞傝亰澶╂ā鍨嬩负鈥滃緟鍔犺浇鈥濓紱鑱婂ぉ鍐呯敓鍥捐矾寰勶紙ChatImagePromptBridgeOperations锛夐渶瑕佸凡閫夎亰澶╂ā鍨嬫墠鑳芥暣鐞嗘彁绀鸿瘝锛屼絾鐩存帴鍥剧墖璺緞鍚屾牱鏃犳彁浜ょ粨鏋滐紝**鏍瑰洜鏈畾**锛堟湭鎶?no-op 鍐欐垚閫氳繃锛夈€?- 璋冭瘯鍏ュ彛 `LocalImageStoreSmokeActivity` 鐩存帴璋冪敤 `LocalImageProvider`锛坄runLocalImageGeneration`锛夛紝**涓嶇粡杩?* `LocalImageWorkerExecution` 鐨?`NativeMediaExecutionGate.withLease`锛屽洜姝や笉鑳戒綔涓轰徊瑁佽瘉鎹紝鏈娇鐢ㄣ€?- 缁撹锛?*image脳video 鐪熷疄 native 浠茶 NOT_RUN/BLOCKED**銆傛仮澶嶆潯浠讹細绌洪棽璁惧 + 宸插姞杞借亰澶╂ā鍨嬶紙璧拌亰澶╃敓鍥炬ˉ锛夋垨鍙‘璁や細鎻愪氦浠诲姟鐨勭洿鎺ュ浘鐗囬〉璺緞锛涚劧鍚庡悓鏃舵彁浜よ棰戯紝瑙傚療瑙嗛 run 鍦?gate 涓婄殑绛夊緟涓庡浘鐗囦换鍔′覆琛岋紱涓嶅緱鐢?debug 鐩磋皟璺緞鎴?UI 绌虹偣缁撴灉浠ｆ浛銆?
### 4. device-b 鑱婂ぉ/DSH 鍥炲綊锛堝閮?lane 鍗犵敤锛孨OT_RUN锛?
- device-b 褰撳墠鍖呰鍏朵粬 lane 鏇存崲涓?`4ef1a121鈥锛?50,679,424B锛宭astUpdateTime 21:30:13锛夛紝宸蹭笉鏄?round-3 鐨?cff571c5锛涘彟涓€ lane 鍦?device-b 涓婂姞杞?qwen3-4b 骞惰繘鍏ョ湡瀹?DSH 椤甸潰/鍙戣捣 DSH turn锛坙ogcat `McaDshLocalGate loaded=true`銆乄ebView probe complete锛夛紝`:local_chat` PSS 宄板€?5,083,623KB銆乼hermal_zone1 71.5鈩冦€?- 鎸夊苟鍙戣鍒欙紝鏈細璇?*鏈?clean launch銆佹湭鎶㈢劍鐐广€佹湭鍙戞秷鎭?*锛氳亰澶╁彂閫?0 鏉°€丏SH 浜や簰 0 娆?鈫?**NOT_RUN/BLOCKED**锛涗粎钀界洏鍙鏃佽瘉銆傛姤鍛?`round4-device-b-chat-dsh-report.md`銆傛仮澶嶆潯浠?鐙崰绐楀彛 + 鍙拷婧骇鐗?+ 宸插姞杞借亰澶╂ā鍨嬨€?- 鍥犳 round-3 鐨?device-b 璇佹嵁锛坈ff571c5锛夊彧鑳戒綔涓哄巻鍙蹭骇鐗╄瘉鎹紝涓嶈兘浠ｈ〃褰撳墠瑁呮満銆?
### 4a. device-a 鑱婂ぉ妯″瀷瀵煎叆灏濊瘯锛堟湭瀹屾垚锛岃亰澶?smoke 浠?NOT_RUN锛?
- 浠?device-b 鎷夊彇 `qwen-test.gguf`锛?28,730,208B锛宻ha256 `7671c0c304e6ce5a7fc577bcb12aba01e2c155cc2efd29b2213c95b18edaf6ed`锛夊苟 push 鍒?device-a `/sdcard/Download/qwen-test.gguf`锛屽彟澶嶅埗鍒?`/sdcard/Documents/qwen-test.gguf` 渚?DocumentsUI 鍒嗙被璁块棶銆?- 缁忕敓浜?UI锛堟ā鍨嬬鐞嗏啋鏈湴鈫掑鍏?GGUF/LiteRT-LM/MNN鈫掗€夋嫨鏂囦欢/ZIP锛夋墦寮€ SAF锛汥ocumentsUI 鐨勬祻瑙?鏂囨。鍒嗙被/鎼滅储鍦ㄨ璁惧涓婂潎鏈兘瀹屾垚閫夋嫨锛氭枃浠跺す琛岀偣鍑讳細閫€鍑洪€夋嫨鍣紝鏂囨。鍒嗙被鍙樉绀烘枃鏈枃浠讹紝鎼滅储鏃犵粨鏋溿€傛湭浜х敓瀵煎叆浠诲姟銆佹湭鍔犺浇妯″瀷銆佹湭鍙戦€佽亰澶╂秷鎭?鈫?**鑱婂ぉ smoke NOT_RUN**锛涘簲鐢ㄦā鍨嬬洰褰?鐢ㄦ埛鏁版嵁鏈淇敼銆?- 涓存椂鏂囦欢 `/sdcard/Download/qwen-test.gguf`銆乣/sdcard/Documents/qwen-test.gguf` 涓庝富鏈?`D:\mca-round4\qwen-test.gguf` 淇濈暀渚涗笅涓€杞紱鎴浘 `round5-model-panel/hub/chat-picker/chat-file-picker/picker-browse/doc-picker/docs-category/other-category/picker-search/chat-import-picker-state.png`銆備笅涓€杞缓璁敼鐢ㄦā鍨嬬鐞嗏€滄帹鑽?涓嬭浇鈥濆叆鍙ｄ笅杞藉彲鑷姩娉ㄥ唽鐨勫皬妯″瀷锛屾垨淇€?SAF 閫夋嫨璺緞銆?
### 5. 浜х墿 C 鐪熷疄 T2V 鍥炲綊

- run `v-e4c7648f-068c-4776-9304-101991f6592f`锛?4脳64脳9脳1銆乸rompt `a red ball bouncing on a woden table`锛坰napshot promptSha256 澶嶆牳涓€鑷达級锛?*COMPLETED/RELEASED**锛?43s锛坉river锛? JSON 243s锛涘嘲鍊?PSS 8,495,324KB锛涚數姹?37.0鈫?7.5掳C / zone0 宄板€?46.46掳C锛涜繘搴?0/1鈫?/1锛屾棤寮傚父銆?- MP4 `media-b61018cc鈥 2,596B锛? 甯?64脳64/16fps/0.5625s锛汥B sha=鎷夊彇 sha=`78e0286cfb89267f3a9cbe6bca5bdaf2f6ddbd024c33483af3acaa943b88647d`锛岄€愬抚鍧囧€?226鈫?54銆佸抚闂村樊 0.2鈥?2.5銆?*濯掍綋/娴佺▼ PASS锛涚敾闈㈣繎鐧戒笉鍙鲸璇嗭紙1 姝ユ渶灏忚鏍笺€侀殢鏈虹瀛愬鑷寸殑杈撳嚭宸紓锛?*锛屽啀娆¤瘉鏄?64脳64脳9脳1 涓嶈兘浣滀负浜у搧/璇箟璇佹嵁銆?- 璇佹嵁锛歚round4-t2v-artifactC-driver.log`銆乣round4-t2v-artifactC-result.json`銆乣round4-t2v-artifactC.mp4`銆乣round4-t2v-artifactC-frames/`銆乣round4-db-summary.txt`锛坮un/浜嬩欢锛決UEUED鈫扖OMPLETED 浜嬩欢宸?240.3s锛夛紱瑕嗙洊/鏀瑰悕璇存槑瑙?`round4-t2v-README.txt`銆?
### 6. 鏈疆鐭╅樀

| 椤?| 鐘舵€?| 璇佹嵁 / 缂哄彛 |
| --- | --- | --- |
| app 瑙嗛 + policy JVM | **PASS 140/0** | init script 鎺掗櫎骞跺彂鍧忔枃浠讹紱鍏?2 娴嬭瘯 NOT_RUN |
| feature workspace JVM | **PASS 32/0** | 鍚?retry + a11y 瀹堝崼 |
| 璁惧浠櫒鍖栵紙浜х墿 C锛?| **PASS** | run15 OK(9) |
| 鏃犻殰纰嶈涔夛紙绔彛/鑺傜偣鍒楄〃/鎶藉眽锛?| **PASS锛堣涔変慨澶嶅凡涓婃満锛?*锛汿alkBack 瀹炶 NOT_VERIFIED | XML 璇佹嵁锛涚鍙?22dp 鏈慨 |
| 鍥剧墖寮曟搸鐪熷疄瀵煎叆 | **PASS** | 5.24GB Anima锛岀敓浜?UI 瀵煎叆 |
| 鐪熷疄鍥剧墖浠诲姟鍚姩 | **BLOCKED** | 鐢熶骇 FAB no-op锛屽師鍥犳湭瀹?|
| image脳video 鐪熷疄浠茶 | **NOT_RUN/BLOCKED** | 鏃犵湡瀹炲浘鐗囦换鍔★紱debug 鐩磋皟鏃犳晥 |
| 鑱婂ぉ/DSH 鐪熷疄鍐掔儫 | **NOT_RUN/BLOCKED** | device-b 琚閮?lane 鍗犵敤锛?ef1a121锛?|
| 浜х墿 C T2V 濯掍綋鍥炲綊 | **PASS锛堟祦绋?濯掍綋锛夛紱璇箟 NOT_MET** | 243s/8.50GB/MP4 9 甯?sha `78e0286c鈥锛涘抚杩戠櫧涓嶅彲杈ㄨ瘑 |
| V1 瀹屾垚鍒ゅ畾 | **浠嶆湭瀹屾垚** | 浜у搧棰勭畻/V1-06 NOT_MET锛涗徊瑁?鑱婂ぉ鍥炲綊缂哄彛 |

### 7. 鍥為€€鐐?
- 鏈疆浠ｇ爜锛氳繕鍘?`VideoGraphCanvas` 鐨勭鍙?鑺傜偣鍒楄〃 desc銆佽繕鍘熸娊灞夆€滃浘鐗団€濆叆鍙ｆ爣绛俱€佸垹闄?`VideoAccessibilityLabelsContractTest`銆?- 璁惧锛歠9d 瀹夎浜嗕骇鐗?C锛坉ebug devicetest 鍖咃級锛涘鍏ョ殑 Anima bundle 鍦?device-a `image_models/anima-preview-1`锛?.24GB锛屽彲缁忕敓浜?UI 鍒犻櫎锛夛紱Download 涓?`anima-preview.zip` 5.63GB 涓烘祴璇曚复鏃舵枃浠讹紝鍙垹闄わ紱鏈Е纰?production 鍖呯敤鎴锋暟鎹€?8a 鏈畨瑁?鏈敼鍔ㄣ€?```

## 2026-10-27 涓ユ牸 V1 楠屾敹缁綔锛堜竴锛夛細浜у搧璺嚎銆佺湡瀹炶瘉鎹笌 gate 缂洪櫡

```text
baseline: HEAD 68b6eef1锛涘叡浜伐浣滃尯 685 鏉?dirty锛涙湰娈垫湭鎻愪氦/鏈帹閫?device/APK: device-a锛涘凡瑁?com.muyuchat.mca.devicetest 涓?.test 鐨?base/test APK
  涓庡綋鍓嶆瀯寤?SHA256 瀹屽叏涓€鑷达紙e337ba0e鈥?/ b66dfd77鈥︼級锛屽彲鐩存帴 instrument
device_state: mDreamingLockscreen=true锛屽畨鍏ㄩ攣灞忥紱UI/鐪熷疄妯″瀷 E2E 褰撳墠 BLOCKED锛堥渶璁惧瑙ｉ攣锛?
璁惧闈?UI instrumentation锛堟湰杞洿鎺?adb锛岀粫杩?Gradle 閲嶈锛?
  command: adb -s device-a shell am instrument -w -r -e class
    NativeVideoBridgeDeviceTest,VideoMp4EncoderDeviceTest,VideoMediaPublisherDeviceTest,VideoRoomMigrationTest
    com.muyuchat.mca.devicetest.test/androidx.test.runner.AndroidJUnitRunner
  result: OK (8 tests)锛孴ime 3.079s锛涜鐩栫紪鐮?鍙戝竷/鍒犻櫎銆?9鈫?2 涓?30鈫?2 杩佺Щ銆丣NI 缂烘ā鍨?闈炴硶璇锋眰杈圭晫
  evidence: build/evidence/v1-06/audit-nonui-instrument.txt

鐪熷疄妯″瀷娴嬮噺锛坒9d锛屾潵鑷棦鏈夎瘉鎹紝鎶芥牱澶嶆牳 ffprobe/甯э級:
  T2V 64x64x9 steps=1: 292.2s锛宲eak PSS 8,511,619KB锛孋OMPLETED/RELEASED
  T2V smoke 64x64x9 steps=1: 321.6s(椹卞姩)/345s(UI)锛宲eak 9,097,725KB锛孋OMPLETED
  I2V 64x64x9 steps=1: 289.2s锛宲eak 8,566,695KB锛孋OMPLETED
  T2V artifact C 64x64x9 steps=1: 243s锛宲eak 8,495,324KB锛孋OMPLETED
  T2V 480x832x33 steps=1: 1377.3s锛?2.95min锛夛紝0 steps锛宯o media锛寃atchdog INTERRUPTED
  T2V 480x832x33 steps=20: 665.8s锛?1.1min锛夊仠鍦?0/20锛孖NTERRUPTED_PROCESS_DEATH
  128x128x9 steps=4: NOT_RUN锛堥┍鍔ㄩ亣鍒伴攣灞?"no cond"/"璇风敤鏁板瓧瀵嗙爜鎴栨寚绾硅В閿?锛?  semantic: 6 涓?64x64 MP4 鍧囨棤鈥滅孩鐞?鏈ㄦ鈥濆彲杈ㄨ瘑鍐呭锛涘抚闂存湁鍙樺寲浣嗚涔?FAIL
  evidence: final-real-t2v-driver.log銆乻moke-64x64-*銆乮2v-64x64-*銆乺ound4-t2v-artifactC-*銆?    build/evidence/v1-06/audit-frames/

鍔犻€熷皾璇曪紙鐪熷疄璁惧锛屽潎澶辫触锛?
  OpenCL: 187s SIGABRT in ggml_backend_opencl_graph_compute锛涙棤 media
  Vulkan: 187s SIGSEGV in vulkan.adreno.so vkCmdBindPipeline 鈫?ggml_vk_mul_mat_q_f16锛涙棤 media
  QNN: BLOCKED锛屼粎鏈?Wan GGUF锛屾棤 Wan ONNX/QNN context锛泂d-native 鏃?QNN 璺緞
  production native: CPU-only锛圕Make SD_VULKAN/GGML_VULKAN/SD_OPENCL/GGML_OPENCL 鍏?OFF锛?
浜у搧璺嚎缁撹:
  - 绉诲姩绔骇鍝侀绠?= NOT_MET锛涗笉鍐欏叆 measuredPresets锛堝綋鍓嶄粛涓虹┖锛?  - 64x64x9x1 鍙兘鏍囦负鈥滃疄楠岃兘鍔涒€濓紝涓嶅緱鍗囨牸涓?V1 浜у搧瑙勬牸
  - 480x832 榛樿妯℃澘鍦?CPU 涓婁笉鍙氦浠橈紱UI 宸插姞鈥滃疄楠岃兘鍔涒€濊鏄庯紝涓嶉潤榛樻敼榛樿鍙傛暟
  - 鍞竴鏈夌晫涓嬩竴姝ユ槸 Vulkan Qualcomm tile 鍥炵Щ spike锛堢害 0.5 澶╋級锛決NN 闇€鏁板懆涓旂己璧勪骇

鏈疆鍙戠幇骞朵慨澶嶇殑鐪熷疄缂洪櫡:
  1) 骞跺彂 gate 鏈鐩栨墽琛岋細LocalVideoWorkerService 鐨?withLease 鏄┖鍧楋紝VideoWorkerCore
     鍦?acquireGate 杩斿洖鍚庢墠 runExecutor锛屽鑷?JNI 瀹為檯鏈寔鏈夊叡浜墿鐞嗛攣銆?     淇锛歏ideoWorkerCore 鏀逛负 withGate(notifyWaiting, block)锛屽湪 block 鍐?runExecutor锛?     service 鐢?NativeMediaExecutionGate.withLease { block() }锛涙柊澧炲洖褰掓祴璇?     鈥渆xecutor runs inside the shared gate lease鈥濄€?     鈿?涔嬪墠鎵€鏈夌湡瀹?64x64 鍑虹墖鍧囧湪鈥滄湭鎸?gate鈥濈姸鎬佷笅瀹屾垚锛涚湡瀹炲浘鐗嚸楄棰戠珵浜変粛 NOT_RUN锛?     涓斾慨澶嶅悗蹇呴』閲嶆柊璺戜竴娆＄湡瀹?64x64 鎵嶈兘绉?gate 淇宸插湪鐢熶骇閾捐矾楠岃瘉銆?  2) UI 绔彛瀹為檯瑙︽帶鍖?48脳22dp锛氭敼涓?48dp 琛岄珮/48dp 鍛戒腑鍖猴紙鍙鍦嗙偣浠?16dp锛夛紝
     杩炵嚎鍧愭爣涓庤嚜鍔ㄥ竷灞€鍚屾锛圢ODE_PORT_BASE=90, STEP=48, 琛岃窛 300锛夈€?  3) 鑺傜偣鍒楄〃涓嶅彲婊氬姩锛?鈥? 鍙锋ā鏉胯妭鐐规棤娉曢€氳繃鎸夐挳璺緞杩炴帴锛氬姞 verticalScroll銆?  4) 鑺傜偣鎽樿 18 瀛楃纭埅鏂細summaryOf 杩斿洖鍏ㄦ枃锛涚敾甯?閾炬潯/鍒楄〃瑙嗚浠?2 琛岀渷鐣ワ紝
     semantics 涓庡弬鏁?sheet 鍙鍏ㄦ枃銆?  5) 璇箟锛氳妭鐐瑰彲 focusable/Enter/Space锛涚鍙ｇ敤 clickable+Role锛涜ˉ鍏?selected/
     stateDescription銆乧anvas 鍖哄煙鍚嶃€乸ending liveRegion銆佹ā寮忓垏鎹笌鎾斁鍣ㄧ姸鎬佹弿杩般€?  浠ヤ笂 UI 鏀瑰姩灏氭湭缂栬瘧/鏈仛 TalkBack/瑙嗚澶嶉獙锛堣澶囬攣灞?+ 鏋勫缓閿佽浠栦汉鎸佹湁锛夈€?
VideoSchemaSqlTest:
  - 鎺掗櫎鏈哄埗宸插畾浣嶏細build/round4-apptest-init.gradle(.kts) 鐨?exclude '**/VideoSchemaSqlTest.kt'锛?    浠呰娆?init 鐢熸晥锛涙甯?:app:testDebugUnitTest 涓嶆帓闄?  - 鍘嗗彶锛?2:44 琛ヤ笂 SupportSQLiteDatabase 閲嶈浇鍚庡凡鍙紪璇戯紱22:52/23:52 鏈?2/0 涓?4495/0 鍘嗗彶璁板綍
  - 鏈疆锛氱瓑寰呮瀯寤洪攣锛堜粬浜?deviceTest-local-jobs 鑷?00:08 鎸佹湁锛夛紝灏氭湭鎵ц锛涗笉寰楀紩鐢ㄥ巻鍙插綋鏈疆
  - 椋庨櫓锛氳娴嬭瘯銆乻chemacheck 涓や釜 helper銆乻chemas 28鈥?2 浠嶄负 untracked锛沠resh clone 缂哄巻鍙?schema

璇佹嵁瀹屾暣鎬ф洿姝?
  final-apk-real-t2v.txt 璁板綍鐨勬槸 Vulkan 宕╂簝 run v-048844bb锛?88s, INTERRUPTED锛夛紝
  涓嶈兘浣滀负鈥滄渶缁?APK T2V PASS鈥濈殑璇佹嵁锛涚湡瀹?64x64 T2V PASS 浠?driver log + DB + MP4 hash 涓哄噯銆?
next锛堟寜椤哄簭锛?
  1) 瑙ｉ攣 device-a 鈫?缂栬瘧褰撳墠婧愮爜 鈫?閲嶈窇 6 涓棰?instrumentation 绫伙紙鍚?UI/鎾斁锛?  2) 閲嶈窇鐪熷疄 64x64x9x1锛堥獙璇?gate 淇鍦ㄧ湡瀹為摼璺寔閿侊級鈫?鑻ュ彲锛屽啀娴?128x128x9x4
  3) 杩愯 VideoSchemaSqlTest锛堟棤 init 鎺掗櫎锛夛紝纭 XML tests=2
  4) 鐪熷疄鍥剧墖浠诲姟 RUNNING 鍚庡仛鍥剧墖脳瑙嗛 gate 绛夊緟/涓茶/閲婃斁锛涘惁鍒欎繚鎸?BLOCKED
  5) TalkBack/涓よ疆瑙嗚 QA锛堟祬/娣?闀挎枃鏈?閿洏锛夛紱杩滅▼ ComfyUI 浠?NOT_RUN
```

### round-5 杩藉姞璇佹嵁锛?026-10-27 01:35锛?
```text
1) VideoSchemaSqlTest锛氫笉鍐嶄娇鐢?build/round4-apptest-init.gradle锛涘彈鎺у叆鍙?   :app:testDebugUnitTest --tests 'com.muyuchat.mca.video.data.VideoSchemaSqlTest' --rerun
   鈫?BUILD SUCCESSFUL锛沊ML 澶嶅埗鍒?build/evidence/v1-06/round5-schema-result.xml锛?   tests=2 failures=0 errors=0 skipped=0 time=1.45s锛堜袱涓柟娉曞潎鐪熷疄鎵ц锛?2) feature UI 寮哄埗缂栬瘧锛?feature:video-workspace:compileDebugKotlin --rerun 鈫?EXIT 0
   锛堢鍙?48dp / 鑺傜偣鍒楄〃婊氬姩 / 鎽樿鍏ㄦ枃 / 璇箟鏀瑰姩宸茶繘鍏ュ綋鍓嶆簮鐮佺紪璇戯級
3) deviceTest base锛堝惈 gate 淇锛夛細:app:compileDeviceTestKotlin 鐢卞叡浜瀯寤虹湡瀹炵紪璇戦€氳繃
   锛堜粎 LocalVideoWorkerService 鍙傛暟鍛藉悕璀﹀憡锛夛紱瀹夎鍒?device-a 鍚庨潪 UI 4 绫诲啀娆?OK(8 tests, 2.766s)
   evidence: build/evidence/v1-06/round5-nonui-after-gate.txt
4) 鏂板 opt-in headless 鎺㈤拡 app/src/androidTest/.../VideoNativeBudgetDeviceTest.kt锛?   鐩磋皟鐪熷疄 VideoWorkerPipeline锛堟爣娉?non-product probe锛岄粯璁?assume 璺宠繃锛涢渶 -e videoBudgetProbe true锛夛紱
   璁板綍鑰楁椂/鑷韩 PSS/闃舵/MP4 sha/棣栦腑鏈抚銆傜敤浜庨攣灞忚澶囦笂鐨?64x64 澶嶈窇涓?128x128x9x4 棰勭畻娴嬮噺銆?5) 褰撳墠 deviceTest 浜х墿锛堟湰杞簮鐮佺紪璇?鎵撳寘锛夛細
   base app-deviceTest.apk 451,283,360B sha256 74505395d7b6d01e91e88e5bce7eeea332c919ad15c8e51d7727ae60716e5028
   test app-deviceTest-androidTest.apk 1,653,258B sha256 9d24d8bf37b02212b43f91470e793481ab6e20c1220a6811430cab3dcfd3f0ca
   device-a 瀹夎鎴愬姛锛坙astUpdate 2026-10-27 01:49:36锛夛紱瀹夎鍚庨潪 UI 4 绫诲啀娆?OK(8 tests, 2.766s)
6) headless 鎺㈤拡棣栨 64x64 杩愯锛團AIL锛岄潪浜у搧缁撹锛夛細
   15.1s锛宱utcome=Failed(MODEL_MISSING, "one or more pinned model components are missing")
   鍘熷洜锛氭帰閽?payload 鍙粰 modelRoot锛屾湭缁?native 蹇呭～鐨?ditPath/encoderPath/vaePath锛?   浜у搧 adapter 浼氬啓杩欎笁涓敭锛屾帰閽堥仐婕忋€傚凡琛ラ綈骞剁敤 VideoModelComponentRole 浠?manifest 鐢熸垚璺緞銆?   淇鍚庣殑 test APK 閲嶅缓/閲嶈窇浠嶅緟鍏变韩鏋勫缓閿侀噴鏀撅紱涓嶅緱鎶婇娆″け璐ュ綋浣滀骇鍝佹ā鍨嬪け璐ャ€?7) 璁惧鐘舵€侊細01:44 鏇剧煭鏆傝В閿侊紙MIUI launcher锛夛紝闅忓嵆鍐嶆瀹夊叏閿佸睆锛涙湰杞?UI/TalkBack/鐪熷疄 UI T2V 浠嶆湭鎵ц銆?8) 婧愮爜鎸囩汗锛堥娆★紝115 鏂囦欢锛夛細
   build/evidence/v1-06/round5-source-files.txt 鈫?sha256 251c814d6de372336d243e898f83fd723fbcb6b3ded9e20cca35f9c0f698bc4e
   锛堣ˉ鎺㈤拡淇鍚庨渶閲嶇畻锛?```

## 2026-10-28 v1-07 楠屾敹缁綔锛坮ound-6锛夛細娴嬭瘯璋冨害缂洪櫡銆佺画浼犻妫€銆佸浐瀹?revision銆佺湡瀹炰笁浠跺涓嬭浇涓庣敓浜ч摼澶嶉獙

鏈疆鍩虹嚎锛欻EAD `68b6eef1`锛屽叡浜伐浣滃尯绾?695 鏉?dirty锛圡 369 / ?? 323 / D 3锛宻taged 0锛夛紝鏈彁浜?鏈帹閫併€傛枃妗ｆ鍓嶆渶鍚庝竴鑺傚仠鍦?2026-10-27 01:35锛坮ound-5锛夛紝钀藉悗浜?2026-10-27 10:00鈥?2:39 鐨勬簮鐮佹敼鍔紱鏈疆浠ュ綋鍓嶆簮鐮併€佹祴璇?XML銆佽澶囦笌瀛愭ā鍧楃姸鎬佷负鍑嗛€愰」鏍稿锛屼笉澶嶈堪鏃х粨璁恒€?
### 0. 鏈疆浜х墿韬唤锛堝師濮嬭褰曪級

| 椤?| 鍊?|
| --- | --- |
| deviceTest base APK | `app/build/outputs/apk/deviceTest/app-deviceTest.apk`锛?65,736,181B锛宻ha256 `a2b3577d1132a4728424b26afd4f17768b82968829b2b4e3060d21b076a68084`锛屾瀯寤哄畬鎴?2026-10-28 02:34:28锛圙radle BUILD SUCCESSFUL锛?0m17s锛?1 tasks executed锛?|
| device-a 瀹夎锛坆ase锛?| `adb install -r` 02:38:08 鎴愬姛锛涜澶囦晶 `sha256sum /data/app/.../base.apk` = a2b3577d鈥︼紙涓庢瀯寤虹墿瀛楄妭涓€鑷达級锛寁ersionCode 8 / 0.2.4-devicetest |
| deviceTest androidTest APK | 1,698,308B锛宻ha256 `ed1c16b31bb546d1fd7f9384894607295c197a95309a28bd73d675ef86ed67d1`锛?2:57:34 鏋勫缓锛?2:57:44 瀹夎锛堝惈鏂板 v29鈫抳35 璁惧杩佺Щ娴嬭瘯锛?|
| device-b 琚祴 APK锛堝閮?lane 01:23 瀹夎锛岄潪鏈疆鏋勫缓锛?| 455,598,287B锛宻ha256 `736bcc7dc4438dc1be14d2d067eab0a5a94b3821670815c0972bec41e99f0d22`锛宭astUpdateTime 2026-10-28 01:23:29锛涙墍鏈?device-b 缁撹鍙璇?sha 鎴愮珛 |
| 璇佹嵁鐩綍 | `build/evidence/v1-07/`锛堟湰杞柊澧烇紱鍙﹀惈骞惰 lane 鐨勫悓鐩綍鏂囦欢锛屽懡鍚嶅墠缂€鍖哄垎锛歚jvm-*`銆乣device-*`銆乣ui-*`锛涜縼绉昏瘉鎹湪 `migration-verification.md` 涓?`jvm-*-test-xml/`锛?|

### 1. 鏈疆婧愮爜淇锛堟渶灏忓垏鐗囷紝閫愰」鍙洖閫€锛?
1. **`VideoModelAssetOwnerTest` 娴嬭瘯璋冨害缂洪櫡锛堢湡缂洪櫡锛岄潪鈥滃亣澶辫触鈥濓級**
   - 鐜拌薄锛? 涓敤渚嬩腑 7 涓け璐ワ紝寮傛鐢ㄤ緥璇诲埌鐨勯兘鏄垵濮?`UNKNOWN`锛堝彇娑堢敤渚嬭鍒?`DOWNLOADING`锛夈€?   - 鐪熷洜锛歰wner 杩愯鍦?`TestScope.backgroundScope`锛岃€?`kotlinx-coroutines-test 1.9.0` 鐨?`advanceUntilIdle()` 鏄庣‘鈥?*褰撳彧鍓?backgroundScope 鐨勫崗绋嬫湭澶勭悊鏃跺氨鍋滄鎺ㄨ繘**鈥濓紙瑙?`TestScope.kt` 鏂囨。娉ㄩ噴涓?`TestCoroutineScheduler.advanceUntilIdleOr{ events.none(isForeground) }` 瀹炵幇锛夈€傚師 `awaitState()` 鍙皟鐢ㄤ竴娆?`advanceUntilIdle()` 鍚庣珛鍒昏 StateFlow锛屽悗鍙颁换鍔℃牴鏈病琚┍鍔ㄣ€?   - 淇锛氭敼涓哄湪娴嬭瘯浣撳唴鏀堕泦 StateFlow 鐩村埌杩涘叆缁堟€侊紙`withTimeout(30_000) { observe().first { it.phase in SETTLED } }`锛夛紝鐢?`runTest` 鐨?background-work runner 椹卞姩锛沷wner 閫昏緫鏈敼銆傚彇娑堢敤渚嬪悓鏍锋敼涓虹瓑寰呯粓鎬併€?   - 缁撴灉锛?*10/10 PASS**锛堝師 8 椤?+ 2 椤规柊澧炵鐩橀妫€鐢ㄤ緥锛夛紝0 澶辫触 / 0 璺宠繃锛宍app/build/test-results/...VideoModelAssetOwnerTest.xml`锛坱ests=10锛夈€?
2. **涓嬭浇绌洪棿棰勬鏈墸宸叉湁 `.part` 瀛楄妭锛堢湡瀹炵己闄凤級**
   - 鍘熷疄鐜?`requiredBytes = needed.sumOf { it.sizeBytes }`锛?GB 缁勪欢宸蹭笅杞?5.9GB 鏃讹紝鍙鍓╀綑绌洪棿 < 6GB+256MiB 灏辫鎶?`DISK_FULL`锛屽疄闄呯画浼犲彧闇€ 100MB銆?   - 淇锛氭寜缁勪欢璁＄畻 `sizeBytes - 宸叉湁 .part 闀垮害`锛坄.part` 璺緞涓庝笅杞藉悓涓€ `managedRemoteIdentity`锛岄妫€涓庡疄闄呯画浼犱笉鍙兘鎸囧悜涓嶅悓鏂囦欢锛夛紝鍐嶅姞 256MiB 淇濈暀浣欓噺姣旇緝锛涗笉瓒充粛濡傚疄 `DISK_FULL` 涓斾笉鍙戣捣缃戠粶璇锋眰銆?   - 鏂板鐢ㄤ緥锛歚space precheck only requires the missing tail of a partially downloaded component`锛坄FREE_SPACE_MARGIN+40` 鏃跺師鏈細鎷掔粷銆佷慨澶嶅悗 READY锛夈€乣space precheck still rejects a resume that does not fit`锛坄+30` 鏃朵粛 `DISK_FULL`銆侀浂缃戠粶璋冪敤锛夈€?
3. **鍙彉 `resolve/main` 鈫?鍥哄畾 commit**
   - `Wan22Ti2v5bProfiles` 鏂板 `QUANTSTACK_REVISION = 57437632鈥銆乣CITY96_REVISION = b535255b鈥锛屼簲涓?`sourceUrl` 鍏ㄩ儴鏀逛负 `https://huggingface.co/<repo>/resolve/<commit>/<path>`锛沗revision` 瀛楁鍗宠 commit銆?   - 2026-10-28 HF API锛坄?recursive=true&expand=true`锛夊鏍革細涓や釜浠撳簱褰撴椂 `main` 灏辩瓑浜庤繖涓や釜 commit锛涙爲涓婂悇鏂囦欢 size 涓?LFS oid 涓?pin 瀹屽叏涓€鑷达紙鏄庣粏瑙?MODEL_ASSET_SOURCES 搂5.1锛夈€俙huggingface.co` 涔嬪悗鏀瑰啓 `main` 涓嶅啀鎶婂仴搴蜂笅杞藉彉鎴?`MODEL_CHECKSUM_MISMATCH`銆?
4. **浼犺緭涓绘満绛栫暐锛堢湡瀹炰笅杞界殑鍓嶆彁锛?*
   - 瀹炴祴锛歠9d **鏃犳硶鐩磋繛** `huggingface.co`锛圖NS 174.37.243.85锛孴CP connect 瓒呮椂銆両CMP 100% 涓㈠寘锛夛紝浣嗗彲鐩磋繛 `hf-mirror.com` 鈮?0 MB/s锛圧ange 206锛夈€?   - 鏂板 `core/download/HuggingFaceTransferUrl.kt`锛歚huggingFacePreferredTransferUrl(url)` 澶嶇敤搴旂敤鏃㈡湁鐨?HF endpoint 绛栫暐锛坄DEFAULT_HUGGING_FACE_ENDPOINTS`锛岄閫?`hf-mirror.com`銆佸洖閫€ `huggingface.co`锛夛紱`VideoModelAssetOwner.remoteFor()` 鍙妸 `downloadUrl` 鎹㈡垚浼犺緭涓绘満锛宍repoId/revision/path/provenance` 浠嶅彇鍥哄畾鏉ユ簮锛屽ぇ灏忎笌 SHA-256 pin 浠嶆槸鍞竴楠屾敹鍒ゆ嵁銆?   - 娴嬭瘯锛歚HuggingFaceTransferUrlTest` 3/3 PASS锛坧in 閫愬瓧鑺備繚鐣欍€侀潪 HF URL 涓嶅姩銆乣hf.co` 涔熸敼鍐欙級锛沗VideoModelAssetOwnerTest` 鏂█ `sourceUrl` 浠嶄负 canonical銆乣downloadUrl` 璧伴暅鍍忎笖淇濈暀鍚屼竴 revision銆?
5. **鏈敼鍔ㄩ粯璁よ鏍间笌棰勭畻**锛歚VideoWorkflowTemplates.kt:25-29` 浠嶄负 `480脳832脳33 / 16fps / 20 steps`锛沗measuredPresets` 浠嶄负绌猴紙`VideoModelManifest.kt:53` + 鏂█锛夈€傛湰杞?64脳64脳9脳1 鍙槸**瀹為獙鍑犱綍**鐨?UI 鍙傛暟锛屼笉鏄粯璁ゅ€笺€?
6. **鍏变韩宸ヤ綔鍖轰袱涓閮ㄩ樆鏂紙濡傚疄璁板綍锛?*
   - 02:02 鍙︿竴 lane 鐨?`AgentHostAssembly.kt:338` 寮曠敤灏氭湭鍔犲叆 import 鐨?`OpenAiChatCompletionsBoundedTransport`锛屽鑷?`:app:compileDebugKotlin` 澶辫触锛涙湰浼氳瘽琛ヤ簡涓€琛?import锛堟湭鏀瑰叾閫昏緫锛夊悗 02:22 缂栬瘧 PASS銆?   - 02:52 鍙︿竴 lane 鐨?`app/src/test/java/com/muyuchat/mca/dsh/DshPromptedToolProtocolTest.kt:245` Kotlin 绫诲瀷鎺ㄦ柇澶辫触锛岄樆鏂?`:app:compileDebugUnitTestKotlin`銆傛湰浼氳瘽**鏈紪杈?鏈垹闄?*璇ユ枃浠讹紝浠呯敤浠呮湰娆＄敓鏁堢殑 init script锛坄build/v1-07-apptest-init.gradle`锛夋妸瀹冩帓闄ゅ嚭涓€娆?`:app:testDebugUnitTest`锛?*璇ユ枃浠剁殑鐢ㄤ緥鏈疆 NOT_RUN**锛屼笉寰楄鍏?PASS銆?
7. **閫氱敤璁惧鍑嗗叆锛坥bjective #3锛?*锛氬瑙嗛璺緞 57 涓簮鏂囦欢锛坄feature/video-workspace/src/**`銆乣app/src/main/java/com/muyuchat/mca/video/**`銆乣composition/VideoHostAssembly.kt`锛夊畾鍚戞壂鎻?`Build.MODEL|Build.MANUFACTURER|Build.SOC|ro.product|chipset|DeviceProfile|whitelist|allowlist|isDeviceSupported|supportedDevices`锛?*鏃犱换浣曞懡涓?*锛氫笅杞?瀵煎叆/杩愯鍏ュ彛娌℃湁鎸夎姱鐗囨垨璁惧鐧藉悕鍗曢殣钘忔垨鎷︽埅銆傜湡瀹炶繍琛屼篃鍦ㄤ袱鍙颁笉鍚岃澶囦笂鎴愮珛锛坒9d 2304FPN6DC/Android 16 瀹屾垚涓嬭浇+杩愯锛?8a 25091RP04C/Android 17 瀹屾垚 UI銆佺湡瀹?DISK_FULL 涓庤亰澶╁洖褰掞級锛屾湭鐭?鏈垪璁惧璧板悓涓€閫氱敤璺緞骞剁敱鐪熷疄 native 鍔犺浇/鍑虹墖鍒ゅ畾銆?
### 2. JVM 闂ㄧ锛堝綋鍓嶆爲锛岀湡瀹炴墽琛岋紱鍧囩粡 `McaBuildLock`锛?
| 杩愯 | 鍛戒护鑼冨洿 | 鎵ц閲?| 澶辫触/閿欒/璺宠繃 | 閫€鍑虹爜 | 鏃ュ織 / XML |
| --- | --- | --- | --- | --- | --- |
| contract | `:core:video-contract:testDebugUnitTest --rerun` | 4 suites / **45 tests** | 0/0/0 | 0 | `jvm-video-modules.log`锛沗core/video-contract/build/test-results` |
| workspace | `:feature:video-workspace:testDebugUnitTest --rerun` | 6 suites / **33 tests** | 0/0/0 | 0 | 鍚屼笂 |
| app 瑙嗛 + 璁惧鍑嗗叆 policy | `:app:testDebugUnitTest --tests 'com.muyuchat.mca.video.*' --tests '鈥niversalDeviceAdmissionPolicyTest' --rerun`锛堝惈涓婅堪 init 鎺掗櫎锛?| 18 suites / **161 tests** | 0/0/0 | 0 | `jvm-app-video-policy-r2.log`锛沗jvm-app-video-policy-xml/` |
| app gate 琛ュ厖 | `--tests 'com.muyuchat.mca.NativeMediaExecutionGate*'`锛?`*Video*`锛?| 10 suites / **73 tests**锛堝惈 `NativeMediaExecutionGateConcurrencyTest` 4锛?| 0/0/0 | 0 | `jvm-gate-supplement.log`锛沗jvm-gate-supplement-xml/` |
| 浼犺緭涓绘満绛栫暐 | `:core:download:testDebugUnitTest --tests '*HuggingFaceTransferUrlTest'` | **3 tests** | 0/0/0 | 0 | `jvm-hf-transfer-url.log`锛沗jvm-hf-transfer-url-result.xml` |
| 杩佺Щ鐩稿叧 | `video.data.*`銆乣AgentWorkflowCheckpointMigrationTest`銆乣schema.*`銆?7鈫?8/28鈫?9/32鈫?3/33鈫?4 杈广€乧heckpoint store | 14 suites / **71 tests** | 0/0/0 | 0 | `jvm-migration-tests.log`銆乣jvm-migration-edge-tests.log`銆乣jvm-video-worker-model-tests.log`锛沗jvm-test-xml/`銆乣jvm-edge-test-xml/`銆乣jvm-video-test-xml/` |
| 鏋舵瀯瑙勫垯 | `python tools/architecture/check_architecture_rules.py` | 19/19 | 鈥?| 0 | `architecture-rules.txt` |

- 璇存槑锛歚VideoSchemaSqlTest` 褰撳墠 3 涓敤渚嬨€佺湡瀹?3/3 PASS锛堟棫鏂囨。鈥? 涓敤渚?/ 鏈紪璇?/ NOT_RUN鈥濆凡澶辨晥锛夈€傛湰杞?app 璁℃暟鍚畠锛屼笉鍚鎺掗櫎鐨?DSH 鏂囦欢銆?- 鏈疆**鏈?*璺戔€滃叏浠撴墍鏈夋ā鍧楁墍鏈夋祴璇曗€濓紱涓婅〃鏄寜瑙嗛褰卞搷鑼冨洿閫夋嫨鐨勮寖鍥达紝涓嶈兘鍐欐垚鍏ㄤ粨閫氳繃銆?
### 3. Room v35 杩佺Щ锛堟簮鐮?+ JVM + 璁惧锛?
- 鏉冨▉锛歚ChatSessionStoreRoomDatabase.kt:122 version = 35`锛涙敞鍐岄摼 27鈫?8鈫?9鈫?0锛坴ideo 29_30锛夆啋31鈫?2鈫?3锛坧ark_tokens锛夆啋34锛坉ispatch_outbox锛夆啋35锛坵orkflow_checkpoints锛夛紝鏃犵己鍙ｃ€?- 瀵煎嚭 schema 27..35 鍏ㄥ湪锛坋ntity 鏁?11/26/30/33/34/34/35/36/37锛夛紝`McaSchemaIdentityHashTest` 2/2 PASS 瑕嗙洊 27..35銆?- JVM锛?9鈫?5 閾炬祴璇曠敤鐪熷疄 Migration 瀵硅薄杩佺Щ鍚庝笌 `35.json` 姣斿锛堝垪/affinity/nullability/绱㈠紩/澶栭敭锛? `PRAGMA integrity_check` + 鏃ц淇濈暀锛?2鈫?3/33鈫?4/34鈫?5 閫愯竟鏂█ DDL 涓庢棫琛岋紱鍚堣 71 鐢ㄤ緥 0 澶辫触锛堣 搂2锛夈€?- **璁惧锛堟柊澧烇級**锛歚VideoRoomMigrationTest` 澧炲姞 `migration29To35KeepsChatImageFileRowsAndAddsAgentTables`鈥斺€斿湪 v29 绉嶅叆 chat/image/file 涓夌被鏃ц锛屾墽琛?29鈫?5 鍏ㄩ儴杩佺Щ锛宍MigrationTestHelper` 鐢ㄥ鍑?`35.json` 鏍￠獙缁撴瀯锛屾柇瑷€鍥涘紶 video 琛ㄤ笌 `agent_park_tokens`/`agent_dispatch_outbox`/`agent_workflow_checkpoints` 瀛樺湪銆佹棫琛屽瓨娲汇€俧9d 涓?`am instrument` 缁撴灉 **OK (3 tests)**锛?.69s锛坄device-room-migration-v35.txt`锛夈€?- 瑕嗙洊缂哄彛锛堥潪缂洪櫡锛夛細璁惧渚ф棫鐢ㄤ緥浠嶅彧鍒?v32锛堢幇鐢辨柊澧炵敤渚嬭ˉ鍒?35锛夛紱鏃犲崟涓€ 28鈫?5 杩炵画鐢ㄤ緥锛泂chemas 28鈥?5 涓庣浉鍏虫祴璇曠洰褰曚粛 untracked锛坒resh clone 椋庨櫓锛夛紱`McaSchemaIdentityHashTest` KDoc 浠嶅啓 鈥?7..32鈥濄€傞檷绾т粛涓嶆敮鎸侊紙forward-only锛屽叏閮?additive锛夈€?
### 4. 璁惧鐢熶骇閾撅紙device-a锛岀敓浜?`MainActivity` UI锛屾棤 instrumentation 娉ㄥ叆锛?
**4.1 T2V锛堝疄楠屽嚑浣?64脳64脳9脳1锛屾棫 APK 74505395鈥︼級**锛歅ASS锛堟祦绋?濯掍綋锛夛紝璇箟 NOT_MET銆?- run `v-c0cf9a05鈥 / attempt `v-84d1a76a鈥 / rev `rev-fc34ad7fab8eec7a`锛汥B 浜嬩欢 QUEUED鈫扨REPARING鈫扖ONDITIONING(TEXT_ENCODE)鈫扴AMPLING鈫扗ECODE鈫扙NCODE鈫扨UBLISH鈫扖OMPLETED锛屾€?208.2s锛圠OAD+TEXT_ENCODE 132.6s銆丼AMPLE 58.1s銆丏ECODE 17.0s銆丒NCODE+PUBLISH 1.4s锛夈€?- 宄板€?worker PSS 7,236,570KB锛涗富杩涚▼ 196,551KB锛涚數姹?32.1鈫?5.0掳C锛孴hermal Status 0銆?- MP4 9,308B sha256 `0094df576e157c4af9958027628720e62650f7bdd446a7f7b3b46dd028a9515a`锛圖B 涓庝富鏈烘媺鍙栦竴鑷达級锛宖fprobe锛歨264 64脳64 16fps 9 甯?0.5625s銆佹棤闊宠建銆?- 鎶藉抚 `device-t2v-12-frame{0,4,8}.png`锛氭祬闈掔汗鐞?/ 杩戠櫧 / 娴呯豢鐧斤紝**鏃犲彲杈ㄨ瘑绾㈢悆鎴栨湪妗?*銆?
**4.2 gate 淇鐨勭湡鏈洪獙璇?*锛歅ASS銆?- 鏂瑰紡锛歂DK 缂栬瘧鐨?aarch64 `fcntl(F_SETLK)` 鏁存枃浠跺啓閿佹帰閽堬紝鏀捐繘 app 绉佹湁鐩綍銆佷互 `run-as` 鍦?app uid 鐨?*鐙珛杩涚▼**鎵ц锛堜笌 `:local_video` worker 涓嶅悓杩涚▼锛夛紝閿佺殑鏄悓涓€涓?`files/native_image_execution.lock`銆?- 杩愯涓細worker `/proc/<pid>/fd` 鏄剧ず fd 120 鈫?`鈥?files/native_image_execution.lock`锛涘閮ㄦ帰閽堜袱娆¤繑鍥?`BUSY:Try again:11`銆傝繍琛岀粨鏉熷悗鍚屼竴鎺㈤拡 **ACQUIRED**锛坙ease 宸查噴鏀撅級銆?- 缁撹锛氱墿鐞嗛攣鍦ㄦ暣涓墽琛屼綋锛堝惈 JNI/缂栫爜锛夋湡闂寸敱 worker 杩涚▼鐪熷疄鎸佹湁锛況ound-5 鐨?`withGate { runExecutor }` + `withLease { block() }` 淇鍦ㄧ湡瀹炵敓浜ч摼鎴愮珛锛坄device-d2-08-gate-busy-during-run.txt`锛夈€?
**4.3 I2V锛堢湡瀹炲浘鐗囪緭鍏ワ紝64脳64脳9脳1锛?*锛歅ASS锛堟祦绋?濯掍綋/杈撳叆缁戝畾锛夛紝璇箟閮ㄥ垎 NOT_MET銆?- 閫氳繃鐢熶骇 picker 閫夋嫨鐪熷疄鍥剧墖 `/sdcard/Pictures/mca-i2v-redball.png`锛泂napshot `inputRefs` 鍚?`IMAGE_REF content://鈥?mca-i2v-redball.png`銆?- run `v-1e4563fa鈥锛?44.1s锛屽嘲鍊?worker PSS 7,514,842KB锛岀數姹?33.3鈫?6.0掳C锛汳P4 2,017B sha256 `34ff411d178295f81bea4482273306cd6694db76ecd5c8b3dc1a17e71dde36d5`锛坔ost=DB锛夛紝h264 64脳64 16fps 9 甯ф棤闊宠建銆?- 鎶藉抚锛歠rame0 鍙**绾㈢悆涓讳綋**锛堣摑搴曠孩鐩橈級锛宖rame8 杩戠櫧鈥斺€? 姝ュ疄楠屽嚑浣曚笅涓讳綋鍙湪棣栧抚淇濈暀锛?*涓嶆瀯鎴愬彲杈ㄨ瘑鐭墖**銆?
**4.4 閿佺瓑寰呬腑鍙栨秷**锛歅ASS銆?- 澶栭儴鎺㈤拡鎸侀攣鍚庢彁浜よ繍琛岋細UI 鏄剧ず鈥滃噯澶囦腑 / 闃舵锛氭棤闃舵鈥濓紙鍗?`onWaitingForLease` 鐨?`VideoRunStage.NONE`锛夛紝worker 鍋滃湪 lease 绛夊緟寰幆銆?- 鍙栨秷锛欴B `CANCEL_REQUESTED`鈫抈CANCELLED` 131ms锛?*浠庢湭鍑虹幇 TEXT_ENCODE/SAMPLE 浜嬩欢**銆佹棤濯掍綋銆乺esourceState RELEASED锛沀I 2.7s 鍚庢樉绀衡€滃凡鍙栨秷鈥濓紙褰撴椂澶栭儴鎸侀攣浠嶅湪锛夈€?- 璇ヨ矾寰?owner 浠嶅啓 `CANCELLED_AFTER_NATIVE_STOP` 閿欒鐮侊紱鈥渘ative 浠庢湭寮€濮嬧€濈殑璇佹嵁鏄樁娈典簨浠剁己澶憋紝涓嶆槸閿欒鐮併€?
**4.5 閿侀噴鏀惧悗鎺堜簣**锛歅ASS銆傞噸璇曡繍琛屼粠 03:00:04 绛夊埌 03:00:35.299 澶栭儴閲婃斁锛岄殢鍚庢墠杩涘叆 TEXT_ENCODE锛涢噴鏀惧墠鏃犱换浣曢樁娈典簨浠躲€?
**4.6 缁堟€侀噸璇?*锛歅ASS銆侰ANCELLED 鍗＄偣鈥滈噸璇曗€濃啋 鏂?run `v-ad569650鈥/attempt `v-b6fc3ae3鈥锛?*鍚屼竴 revision** `rev-825249dab1bef611`锛屽師缁堟€佷笉鍙橈紱閲嶈瘯璺?COMPLETED锛?37.4s锛屽嘲鍊?7,738,067KB锛夛紝鍙戝竷 `media-d7bcd9f6鈥 5,368B sha256 `34b0b567a6d95b6b4e45daae85cbac16dae5c9293b4fddf81ab466d8e71b1c89`銆?
**4.7 杩涚▼姝讳骸鎭㈠**锛歅ASS銆侰ONDITIONING/TEXT_ENCODE 闃舵 `am force-stop` 鍙岃繘绋嬶紱閲嶅紑 MainActivity 鈫?鍗＄墖鈥滃凡涓柇 / 璧勬簮 閲婃斁鏈‘璁?/ INTERRUPTED_PROCESS_DEATH鈥濓紝DB run `v-d9dc581f` state INTERRUPTED銆乺esourceState UNCONFIRMED銆?
**4.8 濯掍綋鍔ㄤ綔**锛歅ASS銆傛挱鏀撅紙鐪熷疄 MediaCodec `c2.android.avc.decoder` 閰嶇疆 + VideoView surface 鍑哄抚锛夈€佸垎浜紙绯荤粺 ChooserActivity锛夈€佸彟瀛橈紙SAF 鈫?`/sdcard/Download/MCA_video_20261028_030843.mp4`锛?,368B sha256 涓?DB 涓€鑷达級銆佸垹闄わ紙纭妗?鈫?`video_media_assets` 琛岀Щ闄ゃ€佺鏈?mp4 鍒犻櫎锛屽叾浠栧獟浣撲笉鍔級銆?
**4.9 涓嬭浇鍚庣湡瀹炲姞杞?+ T2V锛堝綋鍓嶆爲 APK a2b3577d鈥︼級**锛歅ASS銆傚綋鍓嶆爲 APK a2b3577d鈥︺€佸垰鐢辫棰戦〉鐪熷疄涓嬭浇骞舵牎楠岀殑涓変欢濂楋細run `v-鈥锛坈2锛塓UEUED 03:37:55.389 鈫?TEXT_ENCODE 03:38:15.687 鈫?SAMPLE 03:40:43.424 鈫?DECODE 03:41:58.997 鈫?COMPLETED 03:42:00.638锛屾€?**245.2s**锛屽嘲鍊?worker PSS 7,724,664KB锛岀數姹犺嚦 38.8掳C锛圱hermal Status 0锛夛紱MP4 `media-58cc52e6鈥 2,870B sha256 `75cebb06157d59382ea5b958051738c903261dbc3c69e702c62dea7be199c867`锛圖B 涓庝富鏈烘媺鍙栦竴鑷达級锛宧264 64脳64 16fps 9 甯ф棤闊宠建銆傚嵆锛氫笅杞藉悗瀛楄妭鑳借鐪熷疄 native 杞藉叆骞跺彂甯冿紙`device-c2-C2SUMMARY.md`銆乣device-c2-*`銆乣db-c2/`锛夈€?
### 5. 鐪熷疄涓変欢濂椾笅杞斤紙缁忕敓浜ц棰戦〉锛?
- 鍓嶇疆锛氭妸宸叉牎楠岀殑涓変欢濂?*杩炲瓧鑺傛惉鍒?* `files/video_models_backup_20261028-031202`锛堝ぇ灏?璁惧渚?sha256 澶嶆牳浠嶇瓑浜?pin锛夛紝浣块〉闈㈠洖鍒?`MODEL_MISSING`锛涘叏绋?`global http_proxy=null`銆佹棤 adb reverse 闅ч亾銆?- 鐐瑰嚮鈥滀笅杞界己澶辩粍浠垛€濓紙03:13:48锛夆啋 椤甸潰鑷姩涓茶涓嬭浇 DiT鈫扷MT5鈫扸AE锛岃繘搴︿笌纾佺洏 `.part` 鍚屾澧為暱锛?*19m25s** 鍒?`涓変欢濂楀凡灏辩华 / 9.8 GB / 9.8 GB`锛?3:33:13锛夛紝鎸佺画 鈮?2鈥?3 MB/s锛堝嘲鍊?15.7 MB/s锛夈€?- **鍙栨秷**锛?3:15:30 鐐光€滄殏鍋滀笅杞解€濃啋 03:15:31 `CANCELLED`锛宍.part` 1,155,031,720B 涓?`.part.resume.properties` 淇濈暀锛屾寜閽彉鈥滅户缁笅杞解€濄€?- **缁紶**锛?3:16:01 缁х画 鈫?`.part` 浠?1,155,031,720 鍗曡皟澧為暱鍒?1,545,244,194锛?0s 鍐咃級锛屾病鏈変粠 0 閲嶆潵锛坄ResumableDownloader` 鍦ㄩ潪 206 鏃朵細 `resetPartial`锛屾晠杩欐槸鐪熷疄 Range 缁紶锛夈€?- **寮哄埗澶辫触涓庢仮澶?*锛?3:17:38 `svc wifi disable` 鈫?03:18:58 `缁勪欢鑾峰彇澶辫触 / DOWNLOAD_FAILED / 涓嬭浇鏈畬鎴愶紝宸蹭繚鐣欎复鏃舵枃浠讹紙2.08 GB锛夆€?鏈€鍚庨敊璇細Unable to resolve host "hf-mirror.com"`锛沇i-Fi 鎭㈠鍚庡悓涓€鎸夐挳浠?2,231,842,648B 缁х画銆?- **鏈€缁堟牎楠?*锛氳澶囦晶 `sha256sum` 涓夋枃浠?= pin锛圖iT `fcf40dd6鈥 3,029,086,560B锛沀MT5 `2521d4de鈥 6,043,068,256B锛沄AE `e40321bd鈥 1,409,400,960B锛夛紝鎬?10,481,555,776B锛涘簲鐢ㄨ嚜韬啀鏄剧ず涓変釜鈥淪HA-256 宸茬‘璁も€濆苟杩涘叆 READY锛涘浠界洰褰曞湪鏍￠獙閫氳繃鍚庢墠鍒犻櫎銆?- 浼犺緭涓绘満璇佹嵁鐨?*璇氬疄杈圭晫**锛氭棤 root/鏃?tcpdump/鏃?MITM锛屾棤娉曟姄鍙?app 鑷繁鐨?TLS锛涘凡鐩存帴璇佹槑鐨勬槸鈥渁pp 鑷繁瀹屾垚浜嗕紶杈擄紙UI 杩涘害+纾佺洏 `.part`+璁惧渚х湡瀹炲搱甯岋級鈥濃€渁pp 瑙ｆ瀽骞跺皾璇?`hf-mirror.com`锛堝叾鑷韩閿欒涓诧級鈥濃€渀huggingface.co` 鍦ㄨ璁惧涓嶅彲杈撅紝鎴愬姛浼犺緭涓嶅彲鑳芥潵鑷畠鈥濄€俙hf-mirror.com/<repo>/resolve/<commit>/鈥?鈫?302 鈫?cas-bridge.xethub.hf.co/鈥 鏄悓涓€ URL 鐨勮澶囦晶澶嶇幇锛?*涓嶆槸** app 鎶ユ枃鐨勬姄鍖呫€?
### 6. device-b锛氳棰戦〉 UI / 鏃犻殰纰?/ 娴呮繁鑹?/ 鑱婂ぉ / DSH / 杩滅

琚祴浜х墿 sha256 `736bcc7d鈥锛堝閮?lane 01:23 瀹夎锛涢潪鏈疆鏋勫缓锛岀粨璁哄彧瀵瑰畠鎴愮珛锛夈€?
| 椤?| 缁撴灉 | 鍏抽敭瀹炴祴 |
| --- | --- | --- |
| 瑙嗛椤?UI锛堢敾甯?鑺傜偣鍒楄〃/妯″瀷闈㈡澘/杩愯鍗?浣滃搧搴?瀵煎叆棰勮/杩滅▼鍖猴級 | PASS锛堟埅鍥剧骇锛?| 涓変欢濂?`MODEL_MISSING`銆?B/9.8GB锛涜繍琛屽崱澶辫触缁堟€?閲嶈瘯锛涜妭鐐瑰垪琛ㄧ湡婊氬姩锛涘鍏?fixture 鍒も€滃彲杩愯鈥濆悗鍙栨秷鏈惤搴?|
| 鏃犻殰纰嶈涔夋爲 | PASS锛堝疄娴嬶級 | 瑙嗛椤?138 鑺傜偣 / clickable 27 / **鏃犳爣绛?clickable 18**锛? 鎸夐挳瀹瑰櫒+8 绔彛+3 缂╂斁+1 瑙嗗浘鍒囨崲+1 鐢熸垚锛屾爣绛惧湪瀛愯妭鐐癸紝Compose 缁撴瀯闂浠嶅湪锛?|
| 绔彛鍛戒腑鍖?| PASS | **8/8 = 132脳132px = 48.0脳48.0dp**锛坮ound-5 鐨?48dp 淇鎴愮珛锛屽巻鍙?22dp 宸叉秷闄わ級锛涗絾 DiT 杈撳嚭绔彛鐨勫彲鐐瑰嚮瀹瑰櫒琚鎴?82脳132px=29.8脳48dp |
| 鎽樿鎴柇 | PASS | 18 瀛楃纭埅鏂凡娑堝け锛?58 瀛楃鎻愮ず璇嶅叏鏂囪繘 semantics锛岃瑙?2 琛岀渷鐣?|
| TalkBack 鍙惉鏈楄 | **NOT_RUN** | 鏈嶅姟鍙粦瀹氥€佹棩蹇楄 SpeechControllerImpl 璇濊锛屼絾鍚敤鍗冲脊灏忕背鈥滅郴缁熻闊冲紩鎿庘€濈涓夋柟鍗忚 + 7 椤垫暀绋嬮伄缃╋紝鏈唬鐢ㄦ埛鍚屾剰 |
| 娴?娣辫壊 | PASS锛堢幇鐘惰褰曪級 | 涓よ壊涓嬫ā鍨嬮潰鏉?鐢诲竷/杩愯鍗?閿欒鐮?鎸夐挳鍧囧彲璇伙紱鏈仛 before/after 涓よ疆绮句慨锛堟湭鏀逛唬鐮侊級 |
| 涓嬭浇锛?8a锛?| 鐪熷疄 `DISK_FULL` | 鍙敤 4.6GB / 闇€ 9.8GB 鈫?鈥滃瓨鍌ㄧ┖闂翠笉瓒筹細杩樺樊绾?5.4GB鈥濓紝涓嬭浇浠庢湭 active锛坄鏆傚仠涓嬭浇` NOT_RUN锛夛紝闅忓悗鈥滈噸鏂版鏌モ€濇仮澶?`MODEL_MISSING`锛屾棤娈嬬暀 `.part` |
| 鑱婂ぉ锛堢湡瀹炲彂閫侊級 | PASS | qwen-test(CPU) 3s 灏辩华锛岀湡瀹炲洖澶?鈥?+1 equals 2.鈥濓紝23.6 tok/s锛汥B `chat_messages=2` |
| 鍥剧墖鐪熷疄鐢熸垚 | **NOT_RUN/BLOCKED** | 鏃犵敓鍥惧紩鎿庯紙鍥剧墖椤垫槑纭┖鎬侊紝鐐瑰嚮璺虫ā鍨嬬鐞嗭級锛宍image_assets=0`锛涙湭鐢?stub 浼€?|
| DSH | 鍚姩 PASS | `PROCESS_EXITED` 鈫?鈥滈噸鏂板惎鍔ㄢ€?鈫?瀹樻柟鍓嶇鍔犺浇锛坬wen3-4b-instruct PROMPTED_TOOL_V1锛夛紱鏈彂 DSH turn |
| 杩滅 ComfyUI | **BLOCKED** | 涓绘満 8188/8189/8000 鍏ㄦ棤鐩戝惉銆乧url exit=7锛涗富鏈?8000鈥?999 浠?8080(svchost)/8834(nessusd)锛涙枃妗ｄ粎绀轰緥 192.168.1.10:8188锛堜笉鍙揪锛夛紱璁惧 UI 鈥滄祴璇曡繛鎺モ€濆瀹炶繑鍥?`澶辫触锛歊EMOTE_TIMEOUT` |

### 7. 鐢昏川涓庨绠楋紙濡傚疄锛屼笉鍗囨牸锛?
- 64脳64脳9脳1锛? 姝ワ級**涓嶆槸浜у搧瑙勬牸**锛歍2V 鏃犺鲸璇嗕富浣擄紱I2V 浠呴甯т繚鐣欑孩鐞冦€佸悗甯ц繎鐧姐€傚彧鑳界О鈥滅湡瀹為摼璺疄楠岄棴鐜€濄€?- 浜у搧瑙勬牸 `480脳832脳33`锛堥粯璁?20 姝ワ紝`VideoWorkflowTemplates.kt:25-29` 鏈敼锛夛細FAIL锛堝綋鍓嶆爲瀹炴祴锛寃arm-start锛夈€?4脳64 棰勭儹杩愯缁撴潫鍚庣洿鎺ユ彁浜?`480脳832脳33脳1`锛堥粯璁よ鏍?480脳832脳33脳20 鏈敼锛屽彧鏀规湰娆¤繍琛屽弬鏁帮級锛?3:43:33 鎻愪氦 鈫?SAMPLE 03:46:32.686 鈫?**04:06:33.053 琚?20 鍒嗛挓鏃犺繘搴︾湅闂ㄧ嫍缁堟锛?20m00.4s锛屸€滆棰?worker 闀挎椂闂存病鏈夐樁娈垫帹杩涳紝宸茶姹傞€€鍑鸿繘绋嬧€濓級**锛?/1 姝ャ€佹棤濯掍綋浜у嚭锛況unWall **1375.4s锛?2.9min锛?*銆佸嘲鍊?worker PSS **7,149,032KB**銆佺數姹?38.6鈫?1.2掳C锛圱hermal Status 0锛夈€佺粓鎬?**INTERRUPTED**锛堥樁娈?閲囨牱锛岄噴鏀炬湭纭锛夈€傚喎鏈哄娴嬪垽瀹氫负鏃犳剰涔夐噸璺戝苟璁?**NOT_RUN**锛氶噰鏍锋垚鏈?鈮?480路832路33)/(64路64路9) 鈮?**35.7脳** 鍗曟锛?4脳64 鍗曟绾?58s锛屾晠 480脳832脳33 鍗曟 鈮?5min锛屽凡瓒呰繃 20min 鐪嬮棬鐙楋紱round-5 鐨勨€?2.95min / 0 step鈥濈粨璁哄湪鍚屼竴鏉＄敓浜ч摼涓婅澶嶇幇銆?- **缁撴瀯鎬х害鏉燂紙2026-10-28 鏂板锛屾簮鐮佹牳瀹烇級**锛歚LocalVideoWorkerAdapter` 鐨?20 鍒嗛挓鈥滄棤闃舵鎺ㄨ繘鈥濈湅闂ㄧ嫍鍙敱 `onStage` 浜嬩欢澶嶄綅锛坄LocalVideoWorkerAdapter.kt:248-255`锛夛紝鑰?`VideoWorkerPipeline` 鍙湪 native 閲囨牱姝ュ垏鎹㈡椂涓婃姤 SAMPLE 姝ラ锛坄:75-89`锛屾楠ょ矑搴?鏁存暟 step锛夈€傚洜姝?*浠讳綍鍗曟閲囨牱瓒呰繃 20 鍒嗛挓鍑犱綍閮戒笉鍙畬鎴?*锛堜笉鏄參锛岃€屾槸琚湅闂ㄧ嫍鎸夎璁＄粓姝級锛?4脳64脳9脳1 鍗曟瀹炴祴 鈮?8s锛岃€屽崟姝ユ垚鏈瘮鐜囦负 (480路832路33)/(64路64路9)=**357.5脳**锛堟鍓嶈鍐?35.7脳锛屽凡鏇存锛夛紝鏁?480脳832脳33 鍗曟鎸?token 姣?鈮?*1 灏忔椂**锛堢敤 128虏 瀹炴祴绋虫€?36鈥?0s 脳 89.375 鍊?token锛涙棭鏈熺敤 1 姝?58s 脳 357.5 鐨勭畻娉曢珮浼颁负 5.8鈥?.5h锛屽凡鏇存锛夛紝20 姝?鈮?8鈥?0h锛涙棤璁烘€绘鏁板灏戦兘浼氬湪绗?20 鍒嗛挓琚腑鏂€斺€攃3 鐨?+20m00.4s 缁堟姝ｆ槸璇ヨ矾寰勩€備骇鍝佽鏍煎喕缁撳墠蹇呴』鍏堟弧瓒斥€滃崟姝?<20min鈥濓紝鎴栧彟绔嬬湅闂ㄧ嫍绛栫暐鍙樻洿锛堥渶鐙珛璇勫锛屼笉鑳界瓑鍚屾敼棰勭畻锛夈€?- 鍥犳 **V1 浜у搧棰勭畻 NOT_MET銆乂1 鏈畬鎴?*锛沗measuredPresets` 浠嶄负绌猴紝涓嶅緱鍐欏叆鏈祴棰勭畻銆傚姞閫熷悗绔紙OpenCL `ggml_abort` / Vulkan `SIGSEGV` / QNN 缂?Wan 璧勪骇锛夋部鐢?round-5 缁撹锛屾湰杞湭閲嶈瘯銆?
### 8. 鏈繍琛?/ 闃诲 / 椋庨櫓锛堝叏閮ㄥ甫鏉′欢锛?
| 椤?| 鐘舵€?| 鏉′欢 / 璇存槑 |
| --- | --- | --- |
| TalkBack 鍙惉鏈楄 | NOT_RUN | 闇€浜哄伐鎺ュ彈鍘傚晢 TTS 鍗忚骞跺畬鎴愭暀绋嬮伄缃?|
| 鍥剧墖鐪熷疄浠诲姟銆佸浘鐗嚸楄棰戠湡瀹炰徊瑁?| BLOCKED | 涓ゆ満鍧囨棤鍙敤鐢熷浘寮曟搸锛?8a 绌烘€併€乫9d 鏃犲浘鐗囦换鍔¤矾寰勶級锛涙湰杞彧鏈夎棰懨楄棰戦攣绔炰簤璇佹嵁 |
| 128脳128脳9脳4 | NOT_RUN | 鏈疆鏈窇锛涢绠楃粨璁轰笉渚濊禆瀹冿紝浣嗕笉鍐欐垚宸茶鐩?|
| 杩滅 ComfyUI 鎻愪氦/WS/history/涓嬭浇/鍙栨秷 | BLOCKED | 鏃犵湡瀹?endpoint/鍥哄畾 revision |
| `DshPromptedToolProtocolTest`锛堝彟涓€ lane锛?| NOT_RUN | 璇ユ枃浠跺綋鏃朵笉鍙紪璇戯紱鏈細璇濇湭缂栬緫/鏈垹闄わ紱鍏剁敤渚嬩笉璁″叆鏈疆 PASS |
| 鏈窡韪鍓湰 `app/main/java/com/muyuchat/mca/video/model/Wan22Ti2v5bProfiles.kt` | 宸茬煡椋庨櫓 | 涓嶅湪浠讳綍 sourceSet銆佷笉鍙備笌缂栬瘧锛屽唴瀹逛粛鏄棫 `resolve/main`锛涙湰浼氳瘽鏈垹闄わ紝寤鸿鐢辨墍鏈夎€呯Щ闄ゆ垨蹇界暐 |
| 鏈窡韪枃浠?| 椋庨櫓 | schemas 27鈥?5銆乿ideo/agent 娴嬭瘯涓庢簮鐮佺洰褰曚粛 `??`锛宖resh clone 鏃犳硶澶嶇幇 |
| 鍏ㄤ粨鎵€鏈夋ā鍧楁祴璇?/ 鍏ㄩ噺璁惧 instrument | NOT_RUN | 鏈疆鍙窇瑙嗛褰卞搷鑼冨洿涓庤縼绉荤浉鍏抽泦鍚?|

### 9. 鍥為€€鐐癸紙鏈疆 diff锛岄€愰」鐙珛锛?
- `app/src/main/java/com/muyuchat/mca/video/model/VideoModelAssetOwner.kt`锛歚.part` 灏鹃儴棰勬 + 浼犺緭涓绘満锛堝洖閫€鍗虫仮澶嶁€滄暣缁勪欢棰勬 / 鐩磋繛 HF鈥濓紝涓嶅奖鍝嶅凡瀹屾垚鏂囦欢锛夈€?- `app/src/main/java/com/muyuchat/mca/video/model/Wan22Ti2v5bProfiles.kt`锛氬浐瀹?commit锛堝洖閫€涓?`main` 浼氶噸鏂板紩鍏ユ紓绉婚闄╋級銆?- `core/download/src/main/java/com/muyuchat/core/download/HuggingFaceTransferUrl.kt`锛堟柊澧烇紝绾嚱鏁帮紝鍒犻櫎鍗冲洖鍒扮洿杩烇級銆?- `app/src/test/java/com/muyuchat/mca/video/model/VideoModelAssetOwnerTest.kt`锛氳皟搴︿慨澶?+ 2 涓柊鐢ㄤ緥銆?- `core/download/src/test/java/com/muyuchat/core/download/HuggingFaceTransferUrlTest.kt`锛堟柊澧烇級銆?- `app/src/androidTest/java/com/muyuchat/mca/video/data/VideoRoomMigrationTest.kt`锛氭柊澧?v29鈫抳35 璁惧杩佺Щ鐢ㄤ緥锛堝彧澧炰笉鏀癸級銆?- `app/src/main/java/com/muyuchat/mca/composition/AgentHostAssembly.kt`锛氫粎琛ヤ竴琛?import锛堣В闄や粬 lane 鐨勭紪璇戦樆鏂紱濡傝 lane 宸茶嚜琛岃ˉ涓婏紝姝よ鍙幓閲嶏級銆?- `build/v1-07-apptest-init.gradle`锛氫粎鏈杩愯鐨勬帓闄よ剼鏈紙闈炰粨搴撴簮鐮侊紝鏈彁浜わ級銆?- 璁惧渚э細鏃犲垹闄わ紙澶囦唤鐩綍鍦ㄦ牎楠岄€氳繃鍚庡凡鍒狅紱妯″瀷鏂囦欢銆佸獟浣撲笌 DB 鎸夌幇鏈夊绾︽搷浣滐級銆?

## 2026-10-28 v1-08 骞惰楠岃瘉杞細鏃犻殰纰嶄慨澶嶃€佸浘鐗囦换鍔℃牴鍥犮€佸€欓€夎鏍肩煩闃点€佽緭鍏ユ寚绾逛笌鍙鐜扮己鍙?
鏈疆涓?v1-07 涔嬪悗鐨勫苟琛岄獙璇佽疆锛圠ead + 4 涓瓙浠ｇ悊锛歴pec-measure / a11y-fix / image-rootcause / evidence-librarian锛夈€傚伐浣滃尯浠嶄负鍏变韩鑴忔爲锛圚EAD `68b6eef1`锛宻taged 0锛屾湭鎻愪氦/鏈帹閫侊級銆傝瘉鎹洰褰?`build/evidence/v1-08/`銆?
### 1. 瑙嗛椤垫棤闅滅淇锛堟簮鐮?+ device-b 鐪熸満锛岀湡瀹炵己闄峰凡淇級

- 鍩虹嚎锛坴1-07 瀹炴祴锛夛細瑙嗛椤?138 鑺傜偣 / 27 clickable / **18 涓?clickable 瀹瑰櫒鏃㈡棤 text 涔熸棤 contentDescription**锛? 鎸夐挳瀹瑰櫒 + 8 绔彛 + 3 缂╂斁 + 1 瑙嗗浘鍒囨崲 + 1 鐢熸垚锛夛紱DiT 杈撳嚭绔彛鐨勫彲鐐瑰嚮瀹瑰櫒琚涓?82脳132px=29.8脳48.0dp銆?- 淇锛堜粎鍛戒腑鍖轰笌璇箟锛屼笉鏀硅瑙夛級锛氭柊澧?`ui/VideoA11yControls.kt` + 11 涓?ui 鏂囦欢鏀逛负鈥滃崟灞傚彲鐐瑰嚮瀹瑰櫒鑷甫 contentDescription/Role.Button銆佸悎骞跺瓙鑺傜偣鈥濓紱绔彛鎻忚堪鍚€滆妭鐐瑰悕 + 杈撳叆/杈撳嚭 + 绔彛鍚嶁€濓紝缂╃暐/瑙嗗浘鍒囨崲/鐢熸垚甯︾姸鎬侊紱绔彛鍙偣鍑诲尯 `sizeIn(minWidth=48.dp, minHeight=48.dp)`锛屽彲瑙佸渾鐐瑰嚑浣曚笉鍙樸€?- 鐪熸満缁撴灉锛?8a锛岀敓浜?MainActivity锛夛細**鏃犳爣绛?clickable 18 鈫?0**锛?*绔彛鍛戒腑鍖?8/8 = 132脳132px = 48.0脳48.0dp锛?48dp 璁℃暟 0**锛圖iT 杈撳嚭绔彛 29.8脳48.0 鈫?48.0脳48.0 涓斿畬鍏ㄨ惤鍦ㄥ崱鐗囧唴锛夛紱鑺傜偣鍒楄〃 sheet 鏃犳爣绛?clickable 11 鈫?1锛堝彧鍓?Material3 ModalBottomSheet 鎷栨嫿鏉★紱閬僵鈥滃叧闂伐浣滆〃鈥濅笌 Back 浠嶆槸鍙敤鐨勫叧闂矾寰勶級銆傝妭鐐规€绘暟 138鈫?2锛堝悎骞惰涔夋墍鑷达級锛宑lickable 浠?27銆?- 闂ㄧ锛歚feature:video-workspace:testDebugUnitTest` **36 / 0 / 0 / 0锛坋xit 0锛?*锛涘畧鍗祴璇曠粡涓ゆ鍙樺紓璇佹槑闈炴亽鐪燂紙48鈫?0dp 瑙﹀彂绔彛鏂█澶辫触锛涙媶鎺変竴涓寜閽爣绛捐Е鍙戞爣绛炬柇瑷€澶辫触锛涘潎鍥炴粴骞舵牳瀵?SHA-256锛夈€?- 瑙嗚锛氭祬鑹插悓鐘舵€佹埅鍥句笌 v1-07 `ui-04` 鐩告瘮 **0 涓唴瀹瑰儚绱犲樊寮?*锛堜粎鐘舵€佹爮鏃堕挓/鐢甸噺锛夛紱娣辫壊宸紓鍏ㄩ儴鐢?v1-07 褰撴椂鐨勪复鏃惰崏绋跨姸鎬侊紙158 瀛楃鎻愮ず璇?+ 鑺傜偣涓婄Щ 94px锛屽叾 XML 鍙瘉锛夎В閲婏紝鏃犳牱寮?鍑犱綍鍥炲綊銆?- 浜х墿锛歚app-deviceTest.apk` sha256 `24465f54508983785858347eba62fb00252e3340f4513aad9d8c1d4a39be2df7`锛?70,526,159B锛夛紝04:56:55 瀹夎浜?device-b锛堝綋鏃惰澶囦晶 base.apk 鍝堝笇涓€鑷达級銆?*娉ㄦ剰**锛?5:40:49 澶栭儴 lane 鍙堝湪 device-b 瀹夎浜?`c2bdfec8鈥锛?50,887,636B锛宍app/build/outputs/apk/deviceTest` 05:30 鏋勫缓锛夛紝鍥犳涓婅堪 device-b 鏃犻殰纰嶈瘉鎹彧瀵?`24465f54鈥 鎴愮珛锛屽綋鍓?device-b 瑁呮満涓嶅啀鏄浜х墿銆?- 浠?NOT_RUN锛歍alkBack **閫愭帶浠跺彲鍚湕璇?*銆傛湰杞?TTS 瀹為檯鍙戝０锛堟棩蹇椾腑鏈夆€滄湰鍦版ā鍨嬬粍浠讹紝缁勪欢缂哄け鎴栨牎楠屾湭閫氳繃鈥︹€濃€滄墦寮€鍘嗗彶. 鎸夐挳鈥濓級锛屼絾 adb `input tap/swipe` 鏃犳硶鍦ㄨ MIUI TalkBack 涓婂彲闈犻┍鍔ㄦ棤闅滅鐒︾偣锛堢偣鍑昏褰撲綔婵€娲汇€佹粦鍔ㄧ劍鐐逛竴娆″悗鍋滄粸锛夛紝鍥犳 18 涓帶浠舵棤娉曢€愪竴鈥滃惉鍒扳€濈‘璁わ紱璇箟鏍戜笌 TTS 閫氳矾璇佹嵁宸茬暀銆?
### 2. 鍥剧墖浠诲姟鈥滄鎸夐挳鈥濇牴鍥狅紙鍙鍒嗘瀽锛屾湭鏀逛粬浜烘枃浠讹級

- device-b 鐨勨€滅敓鎴?鈫?璺冲埌妯″瀷绠＄悊/鎺ㄨ崘鈥濇槸**璁捐鍐呯┖鎬佸畧鍗?*锛坄ImageWorkspaceScreens.kt:287-290` 鈫?`MainChatScreenActions.kt:250-253`锛夛紝涓嶆槸缂洪櫡锛涗笌 v1-07 `ui-47` 閫愯烦鍚诲悎銆?- device-a 涓娾€滃鍏ユ垚鍔熶絾鐐圭敓鎴愭棤浠讳綍鍙嶅簲鈥濈殑**鍏蜂綋闂搁棬浠?NOT_PROVEN**锛堢己 image 鏃ュ織/UI 鏂囨/job 蹇収/dumpsys 鍥涚被璇佹嵁锛夛紝浣嗏€滀负浠€涔堣〃鐜颁负姝绘寜閽€濆凡璇佹槑锛屼笖鏄彲淇殑鐪熷疄缂洪櫡锛?  - **D1锛堣嚧鍛斤級**锛氬浘鐗囬〉鍞竴鐘舵€佸嚭鍙?`ImageWorkspaceScreens.kt:568` 鍙覆鏌?`GuidanceNotice`锛岃€?`GuidanceNotice` 棣栬 `userNotice(message) ?: return`锛坄UserGuidance.kt:82`锛夆€斺€旇涓嶅嚭鐨勬枃妗堟暣鍧椾笉娓叉煋銆備簬鏄?`ImageGenerationOwner.submit()` 鑷冲皯 5 绫绘嫆缁濓紙`宸叉湁鍥剧墖鐢熸垚浠诲姟姝ｅ湪杩愯鈥 :188銆乣宸叉湁 UI 鎴?Local API 鍥剧墖浠诲姟姝ｅ湪杩愯鈥 :325銆乣鍥剧墖鐢熸垚灏氭湭鍚姩锛?readiness>` :217銆乣鏈湴鑱婂ぉ姝ｅ湪鐢熸垚鈥 :200銆乣Textual Inversion 搴撲粛鍦ㄥ姞杞解€ :239锛夊湪鍥剧墖椤?**0 鍍忕礌鍙**锛涜亰澶╄緭鍏ユ爮鏈夋纭厹搴曪紙`ChatComposerScreens.kt:301-315`锛夛紝鍥剧墖椤垫病鏈夈€?  - **D2**锛歚onGenerateImagePrompt(...)` 杩斿洖 false 鏃朵袱澶勮皟鐢ㄧ偣鏃犲厹搴曪紙`ChatImagesWorkspaceEnqueue.kt:299-305`銆乣ChatImagesWorkspaceOverlay.kt:414-419`锛夛紱**D3**锛氳鍥炶皟榛樿鍊?`= { _, _ -> false }`锛坄ChatScreenActions.kt:108`锛夛紝婕忔帴绾挎椂闈欓粯 false锛?*D4**锛歚submit()` 14 澶勬棭閫€闆舵棩蹇楋紱**D5**锛氬け璐ヤ粎鍦?job 浠?active 鏃跺彂甯冿紙`ImageGenerationOwnerExecution.kt:64-99`锛夛紝鍚﹀垯 job 鍋滃湪 GENERATING锛?*D6**锛氱敾寤婁笉娓叉煋 `jobs`锛坄ImageGalleryHome`锛夛紝鏁呪€滄病鐪嬪埌浠诲姟鍗♀€濅笉鑳借瘉鏄庢病 job銆?- 鏈€灏忎慨澶嶅缓璁紙F1 鐘舵€佸厹搴曘€丗2 false 鍏滃簳銆丗3 姣忎釜鍑嗗叆鎷掔粷鎵撲竴琛屾棩蹇椼€丗4 鍒犻櫎闈欓粯榛樿鍊硷級涓庡垽鍒柟妗堣 `build/evidence/v1-08/image-task-rootcause.md`銆?*鏈疆鏈惤鐩?*锛?4 涓摼璺枃浠朵腑 21 涓琚叾浠?lane 淇敼锛坄M`锛夛紝鎸夊崗浣滆鍒欎笉骞跺彂鏀瑰啓浠栦汉鍐欓泦銆?- image脳video 鐪熷疄浠茶鍥犳浠?**BLOCKED/NOT_RUN**锛堜袱鏈洪兘鏃犲彲鐢ㄧ敓鍥句换鍔★級銆傜粰鍑轰笉鏀逛唬鐮佺殑鍒ゅ埆璺緞锛歀ocal API `POST http://127.0.0.1:11435/v1/images/generations` + Bearer锛岃繑鍥炵粨鏋勫寲閿欒鐮佸彲鍖哄垎鈥滆祫浜?鍓嶇疆鏉′欢鈥濅笌鈥淯I 閫氳矾鈥濓紱鑻?200 鍑哄浘锛岃鐪熷疄 RUNNING 鍥剧墖浠诲姟鍗冲彲鐩存帴鍋?gate 浠茶銆?
### 3. 鍊欓€夎鏍肩煩闃碉紙device-a锛岃繘琛屼腑锛涘厛璁板綍闃诲涓庣畻鏈洿姝ｏ級

- 04:12鈥?0:06 鏈熼棿 device-a 澶勪簬**瀹夊叏閿佸睆**锛坄deviceLocked=1`銆乲eyguard bouncer銆佹埅鍥?FLAG_SECURE 鍏ㄩ粦锛夛紝UI 椹卞姩鐨?4 椤规祴閲忓潎鏃犳硶寮€濮?鈫?褰撴椂璁?**NOT_RUN (BLOCKED: secure keyguard)**锛坄spec-matrix.md`銆乣device-v108-01-access-blocker.txt`锛夈€?0:04 鐢ㄦ埛瑙ｉ攣涓€娆★紝浣嗕唬鐞嗕负璁板綍鍘熷€艰閲嶇疆 `stay_on_while_plugged_in` 瀵艰嚧 60s 鍚庡啀娆￠攣灞忥紙宸插瀹炶褰曪紝鏃犱换浣?run 鎻愪氦锛夛紱10:22 鐢ㄦ埛鍐嶆瑙ｉ攣锛孡ead 璁剧疆 `screen_off_timeout=1800000` + `svc power stayon true` 淇濇姢绐楀彛鍚庨噸娴嬨€?- **绠楁湳鏇存锛堥噸瑕侊級**锛氬崟姝ユ垚鏈瘮鐜囦负 `(480路832路33)/(64路64路9) = 357.5脳`锛屾鍓?v1-07 鏂囨。璇啓 35.7脳锛堝樊 10 鍊嶏級銆傚洜姝?480脳832脳33 鐨?CPU 鍗曟 鈮?**5.8鈥?.5 灏忔椂**锛堜笉鏄?35 鍒嗛挓锛夛紱鐪嬮棬鐙楃粨璁轰笉鍙橈紙绗?20 鍒嗛挓蹇呰涓柇锛夛紝浣嗘棫鏁板瓧涓嶅緱鍐嶅紩鐢ㄣ€?28脳128脳9脳4 鈮?6脳 绾挎€?鈬?閲囨牱 15.5鈥?0.2 min銆佹€?18鈥?3 min锛堟敞鎰忓姏淇 29鈥?0 min锛夈€?- 缁撹锛堝湪鐪熷疄鏁版嵁钀藉湴鍓嶄笉鍐荤粨浠讳綍瑙勬牸锛夛細`measuredPresets` 淇濇寔涓虹┖锛涜嫢鍚庣画娴嬪埌锛?28脳128脳9脳4 涔熷彧鑳戒綔涓?**V1 瀹為獙涓婇檺**锛屼笖蹇呴』甯у唴鐪熺殑鍑虹幇鎻愮ず涓讳綋锛涗骇鍝佺骇鐭墖闇€瑕佸彲鐢ㄧ殑鍔犻€熷悗绔紙绾︿袱涓暟閲忕骇锛夋垨杩滅鍚庣锛屽綋鍓?CPU/OpenCL/Vulkan/QNN/杩滅鍒嗗埆 FAIL/BLOCKED銆?
### 4. 杈撳叆鎸囩汗涓庡彲澶嶇幇缂哄彛锛坋vidence-librarian锛?
- 鎸囩汗 `source-fingerprint.txt`锛欻EAD `68b6eef1`锛涙姌鍙?699 鏉★紙M 369 / ?? 327 / D 3锛夈€佸睍寮€ 4209 鏉★紱瀛愭ā鍧?llama.cpp 20 鑴忋€乻table-diffusion.cpp 18 鑴忥紙鍚祵濂?ggml锛夈€乼okenizers-cpp 5 鑴忥紱APK 瀹炵畻 `a2b3577d鈥锛?65,736,181B锛変笌 `ed1c16b3鈥锛?,698,308B锛変笌鏂囨。涓€鑷淬€傚洜鐢熸垚鏈熼棿鍏朵粬 lane 浠嶅湪鍐欏叆锛?4:23:44鈥?4:27:06 鏀瑰啓浜?11 涓?video-workspace UI 鏂囦欢骞舵柊澧?`VideoA11yControls.kt`锛夛紝缁欏嚭 S1/S2 鍙屽揩鐓э細AGG `009ca166鈥 鈫?`84122b71鈥锛?17鈫?18 鏂囦欢锛夛紝**鑱氬悎鍝堝笇褰撳墠涓嶇ǔ瀹氾紝寮曠敤蹇呴』甯︽椂闂存埑**銆?- 鍙鐜扮己鍙?`untracked-input-inventory.md`锛氳瑙嗛鍔熻兘鍦?fresh clone 鍙鐜伴渶瑕?**144 涓湭璺熻釜鏂囦欢 / 2,321,988B**锛坰chemas 28鈥?5銆乿ideo 涓?娴?androidTest銆乤idl銆乿ideo-contract銆乿ideo-workspace銆乣sd_video_generate.cpp`銆丠F 浼犺緭鍑芥暟銆乤gent 32鈥?5 鏁版嵁/杩佺Щ銆佹柟妗堟枃妗ｏ級+ 369 M + 3 D锛?*鎻愪氦鏈巿鏉冿紝鏈疆鍙嚭娓呭崟**銆傚彟锛歚.dsh-tmp/`锛?2.6MB锛変笌鏍圭洰褰?`tmp_bing.html` 鏈窡韪笖鏈蹇界暐锛宍git add -A` 浼氳甯︼紝寤鸿鍔犲叆 `.git/info/exclude`銆?- 璇佹嵁绱㈠紩 `EVIDENCE-INDEX.md`锛氬叚鐩爣閫愭潯鏄犲皠 172 鏉¤瘉鎹矾寰勶紝鍏ㄩ儴瀹炲瓨銆?- 淇濇姢瀹¤ `safeguard-audit.txt`锛?*PASS**鈥斺€擿AGENTS.md` 涓?`UniversalDeviceAdmissionPolicyTest.kt` 浠嶅湪 `.git/info/exclude`锛堢 7/8 琛岋級锛宍git ls-files` 涓嶆帴鍙楋紝`status --ignored` 鏄剧ず `!!`锛孒EAD 鏈彉锛宻taged 0锛屾棤 `.orig/.rej` 娈嬬暀銆?
### 5. 涓庢枃妗ｇ殑宸茬煡鍋忓樊锛堟湰杞凡璁板綍锛屾湭浼€犱竴鑷达級

1. **鈥滃綋鍓嶆爲 = a2b3577d APK鈥濆凡涓嶆垚绔?*锛歚MainActivity.kt`(04:10)銆乣MainViewModel.kt`(04:03) 涓?11 涓?video-workspace UI 鏂囦欢(04:24鈥?4:27) 鍦ㄨ APK 鏋勫缓涔嬪悗琚敼鍐欙紱device-b 鍙堝湪 05:40 琚閮?lane 鎹㈡垚 `c2bdfec8鈥銆傚洜姝?v1-07 鐨勮澶囪瘉鎹粦瀹?`a2b3577d鈥锛坒9d 浠嶈璇ョ増锛夛紝鏃犻殰纰嶈瘉鎹粦瀹?`24465f54鈥锛?8a 04:56 鏃讹級锛屼簩鑰呴兘涓嶆槸鈥滃綋鍓嶆爲鈥濈殑瀹屾暣閲嶅缓銆備换浣曗€滃綋鍓嶆爲閫氳繃鈥濋』浠ユ柊鏋勫缓 + 鏂板畨瑁?+ 鏂版寚绾逛负鍑嗐€?2. v1-07 婕傜Щ瀹¤鐨?D08锛坓ate 淇 NOT_RUN锛変笌 D13锛堣澶囪縼绉讳粎鍒?32锛夊凡琚?`device-d2-08-gate-busy-during-run.txt`銆乣device-room-migration-v35.txt` 鍙栦唬銆?3. 03:56:06 瀛樺湪涓€娆℃湭琚换浣曟枃妗ｈ褰曠殑 debug 鏋勫缓锛坄c4f54f23鈥锛?65,735,577B锛夈€?4. HANDOFF 搂0 浠嶆槸 2026-10-26 蹇収锛堝凡鍔犳敞锛夛紝搂7 鐨?140/0 宸叉爣娉ㄤ负鏃ч泦鍚堛€?5. 鏈窡韪鍓湰 `app/main/java/.../Wan22Ti2v5bProfiles.kt`锛?,953B锛? 澶?`resolve/main`锛屼笉鍦ㄤ换浣?sourceSet锛変粛鍦紝妫€绱㈤櫡闃辨湭娑堥櫎锛涙湰浼氳瘽鏈垹闄ゃ€?

## 2026-10-28 v1-08 杩藉姞锛?28脳128脳9脳4 鐢熶骇閾句笌鎺㈤拡瀹炴祴锛堥娆￠潪 1 姝ョ殑鐪熷疄鍑犱綍娴嬮噺锛?
璁惧 device-a锛堣В閿佸悗锛夛紝APK 浠嶄负 `a2b3577d鈥锛屼笁浠跺涓?v1-07 鐪熷疄涓嬭浇骞舵牎楠岀殑鍚屾壒瀛楄妭銆?
### 1. 鐢熶骇 UI 鐪熻窇锛圱2V 128脳128脳9脳4锛岀敓浜?MainActivity锛?
| 椤?| 鍊?|
| --- | --- |
| run / attempt / revision | `v-fe254f28-d98a-4f82-9c32-f54329bd1562` / `v-0cf8a54b-a812-4b63-8641-44f55edb8005` / `rev-fab8a38c6068f385` |
| 鍙傛暟 | 128脳128銆? 甯с€?6fps銆?*steps 4**銆乪uler銆乧fg 6.0銆乫low 3.0銆侀殢鏈?seed銆乥ackend LOCAL_NATIVE |
| DB 浜嬩欢 | QUEUED 10:56:16.760 鈫?PREPARE 10:56:16.779 鈫?TEXT_ENCODE 10:56:34.045 鈫?SAMPLE 10:58:51.777 鈫?DECODE 11:04:33.824 鈫?ENCODE 鈫?PUBLISH 鈫?COMPLETED 11:04:35.055 |
| 鑰楁椂 | DB 璺ㄥ害 **498.3s**锛圦UEUED 10:56:16.760 鈫?COMPLETED 11:04:35.055锛涚洃鎺х獥鍙?446.5s 鏄彁浜ゅ悗 73s 鎵嶅紑濮嬶紝鍕挎贩鐢級锛汱OAD+TEXT_ENCODE 137.7s锛涢噰鏍?342.0s锛堝钩鍧?85.5s/step锛岄姝ュ緢閲嶏紝UI 绋虫€佹 37鈥?7s锛夛紱DECODE 0.017s锛汦NCODE+PUBLISH 1.2s |
| 鍐呭瓨/鐑?| 宄板€?`:local_video` PSS **8,083,756KB**锛涚數姹犲嘲鍊?39.6掳C锛汿hermal Status 0 |
| 濯掍綋 | `media-87eae0ea鈥 5,275B sha256 `4b7264491726351e45728da8b8c842312f3cda3392e9acb97a6eb9b5871e53c4`锛堜富鏈烘媺鍙栦笌 DB 涓€鑷达紝浜岃繘鍒跺畨鍏ㄦ媺鍙栭渶 `cmd /c` 閲嶅畾鍚戯紝PowerShell 绠￠亾浼氭崯鍧?MP4锛夛紱h264 128脳128 / 16fps / 9 甯?/ 0.5625s / 鏃犻煶杞?|
| 鎶藉抚鍒ゅ畾 | `device-run1-12-frame{1,2,3}.png`锛堥/涓?鏈級锛氭繁缁?钃濇殫鑹叉ā绯婏紝**鏃犲彲杈ㄨ瘑绾㈢悆鎴栨湪妗?鈫?璇箟 NOT_MET** |

### 2. headless 鎺㈤拡琛ュ厖锛坥pt-in锛岄潪浜у搧閾撅級

- 128脳128脳9脳4锛?*775.9s**锛屽嘲鍊?7,069,836KB锛?4.5掳C锛屽獟浣?14,556B sha `ed670b70鈥锛宲robe 鏍囪 `native_budget_not_product_acceptance`锛涙娊甯у悓鏍蜂负鎶借薄鑹插潡 鈫?NOT_MET銆傛帰閽堝崟姝ョ害 59s锛岀敓浜ч摼 85.5s/step锛堢敓浜ч摼鍚澶栧悓姝?鍥炶皟寮€閿€锛夛紝涓よ€呬笉鍙簰鐩告浛浠ｃ€?- 缁撹锛?*128脳128 鍦?4 姝ヤ笅浠嶄笉鍑轰富浣?*銆備箣鍓嶆墍鏈夊皬鍑犱綍璇佹嵁閮芥槸 **1 姝?*锛?4脳64脳9脳1锛夛紱鏈疆璇存槑鈥滄彁楂樺儚绱犱笉瑙ｅ喅璇箟鈥濓紝**姝ユ暟鎵嶆槸缂哄け鍙橀噺**銆傚洜姝ゆ柊澧炲疄楠?`64脳64脳9脳20`锛堥粯璁?20 姝?/ 鍙傝€冮厤鏂广€佷究瀹滃嚑浣曪級锛岀敤浜庡洖绛斺€滃熀纭€妯″瀷鍦ㄦ枃妗ｆ鏁颁笅鍒板簳鑳戒笉鑳藉嚭涓讳綋鈥濓紝鍏剁粨鏋滃皢鍐冲畾 V1 璇箟闂ㄧ鏄惁鍙兘鍦ㄦ湰鏈鸿揪鎴愶紝鎴栧繀椤讳緷璧栧姞閫?鏇村ぇ鍑犱綍銆?
### 3. 瀵规棦鏈変及璁＄殑鏇存

- v1-08 鍏堝墠鎸?16脳 绾挎€у鎺ㄢ€?28脳128脳9脳4 閲囨牱 15.5鈥?0.2min鈥濓紝瀹炴祴鐢熶骇閾?5.7min 閲囨牱銆佹€?7.4min锛堟鏁?4 鑰岄潪 1 鏃跺崟姝?85.5s锛夛紱澶栨帹鍙彲鐢ㄤ簬閲忕骇鍒ゆ柇锛屼笉寰楀綋娴嬮噺鍊煎紩鐢ㄣ€?- 480脳832脳33 鍗曟鎴愭湰姣?**357.5脳**锛堥潪 35.7脳锛変繚鎸佷笉鍙橈紱鍗曟 鈮?.8鈥?.5h锛屼粛蹇呯劧琚?20 鍒嗛挓鐪嬮棬鐙楃粓姝€?

## 2026-10-28 v1-08 杩藉姞 2锛?4脳64脳9脳20锛堥粯璁ゆ鏁帮級璇箟缁撹涓庢垚鏈彛寰勬洿姝?
鍚屼竴璁惧/浜х墿锛坒9d銆丄PK `a2b3577d鈥銆乿1-07 涓嬭浇鏍￠獙鐨勪笁浠跺锛変笂鍐嶈窇涓€杞?**榛樿姝ユ暟**鐨勫皬鍑犱綍锛屽洖绛斺€滄槸涓嶆槸姝ユ暟涓嶅鈥濄€?
| 椤?| 鍊?|
| --- | --- |
| run / attempt / revision | `v-18982f1b-b8b3-41f8-9979-23ee6cde161a` / `v-3b81360d-3fca-44bb-97ab-354434eabfb2` / `rev-f6220806ff8af7ac` |
| 鍙傛暟 | 64脳64銆? 甯с€?6fps銆?*steps 20**锛堝弬鑰冮厤鏂规鏁帮級銆乪uler銆乧fg 6.0銆乫low 3.0 |
| 鑰楁椂 | DB 璺ㄥ害 **799.6s锛?3.3min锛?*锛涢噰鏍?**643.6s = 32.2s/step**锛涘嘲鍊?PSS **7,978,652KB**锛涚數姹犲嘲鍊?**40.6掳C**锛孴hermal 0 |
| 濯掍綋 | 10,436B sha256 `bbb31588鈥锛宧264 64脳64 / 9 甯?/ 16fps / 鏃犻煶杞?|
| 鎶藉抚鍒ゅ畾 | frame0 骞抽摵娴呴潚 鈫?涓儴杩戠櫧 鈫?鏈抚浜豢鍦?+ 灏忓潡绾㈡锛?*鏃犵悆銆佹棤鏈ㄦ銆佹棤杩愬姩 鈫?璇箟 NOT_MET** |

**缁撹**锛氬湪 64脳64 涓婃妸姝ユ暟浠?1 鎻愬埌 20锛?*鍙槸璁╅鑹插満婕斿寲锛屼粛涓嶄骇鐢熷彲杈ㄨ瘑涓讳綋**銆傛墍浠ュ皬鍑犱綍璇箟澶辫触鐨勪富鍥犳槸**鍑犱綍鍒嗗竷/鏉′欢锛堟ā鍨嬪湪璇ュ垎杈ㄧ巼涓嬩笉鍦ㄨ缁冨垎甯冿級**锛屼笉鏄€滄鏁颁笉澶熲€濄€備粛鏈帓闄も€滄枃鏈潯浠?閲忓寲閾炬湁缂洪櫡鈥濊繖涓€鍙兘锛堣涓嬫柟鍙垽瀹氬疄楠岋級銆?
### 鎴愭湰鍙ｅ緞鏇存锛堥噸瑕侊級

- token 姣?**357.5脳** 姝ｇ‘锛?7.5脳 鍍忕礌 脳 3.667脳 甯э級锛屾浛浠ｆ棭鍓?35.7脳 鐨勭瑪璇€?- 浣嗙敤鈥? 姝?58.1s 脳 357.5鈥濆鎺ㄤ細**楂樹及**锛氶偅 58s 涓昏鏄姝?鍥炬瀯寤哄浐瀹氬紑閿€銆傜敤瀹炴祴鐨?128脳128 绋虫€佹 36鈥?0s 脳 89.375 鍊?token 鎺ㄧ畻锛?80脳832脳33 **鍗曟 鈮?3鈥?0min**锛?0 姝?**鈮?8鈥?0h**锛堜笉鏄?115鈥?50h锛夈€傜湅闂ㄧ嫍缁撹涓嶅彉锛堢 20 鍒嗛挓蹇呰缁堟锛宑3 鐨?0/1 姝ヨ鏉€鍚诲悎锛夈€?- 128脳128脳9脳8 **NOT_RUN**锛欳 缁撴潫鍚庣數姹?40.5掳C 瓒呰繃绾﹀畾鐨?<40掳C 闂ㄩ檺锛屾湭寮鸿璺戙€?- 鎺㈤拡鍙傛暟鍚嶆槸 `width/height/frames/steps/prompt`锛堜笉鏄?`videoProbeWidth/鈥锛夈€?
### 涓嬩竴涓兘鏀瑰彉缁撹鐨?CPU 瀹為獙锛堝緟璁惧瑙ｉ攣锛?
**480脳832脳9脳1**锛氬彧鏈?9 甯ф椂 token 姣?鈮?4.375脳 浜?128虏 绋虫€侊紝閲囨牱绾?**15鈥?7min**銆佹€荤害 18鈥?0min锛?*鍒氬ソ钀藉湪 20 鍒嗛挓鐪嬮棬鐙楀唴**銆傚畠鎶娾€滃垎杈ㄧ巼鈥濅笌鈥滄鏁扳€濊В鑰︼細
- 鑻ュ湪鎺ヨ繎璁粌鍒嗚鲸鐜囦笅浠嶅嚭鎶借薄鑹插潡 鈫?璇存槑**妯″瀷/閲忓寲/鏂囨湰鏉′欢閾炬湁缂洪櫡**锛堢湡瀹?V1 缂洪櫡锛岄渶鎸?搂2 鐨?D1鈥揇6 涔嬪缁х画鏌?conditioning锛夛紱
- 鑻ュ嚭鐜板彲杈ㄨ瘑涓讳綋 鈫?璇存槑灏忓嚑浣曟槸鍒嗗竷闂锛屼骇鍝佽矾绾垮彧闇€瑙ｅ喅绠楀姏/棰勭畻銆?

## 2026-10-28 v1-08 杩藉姞 3锛氬浘鐗囬〉鈥滄嫆缁濅笉鍙鈥濆凡淇苟鐪熸満楠岃瘉锛團1/F2/F3锛?
鎵挎帴 搂2 鐨?D1鈥揇6 鏍瑰洜锛屾湰杞妸鏈€灏忎慨澶嶈惤鐩橈紙鍙姩鍥剧墖鐘舵€侀摼锛? 涓矾寰勶紝鍧囦负浠栦汉宸蹭慨鏀规枃浠朵箣涓婄殑鍙犲姞鏀瑰姩锛屾湭鍋氭棤鍏抽噸鏋勶級锛?
- **F1** `feature/chat/.../ImageWorkspaceScreens.kt`锛氱姸鎬佸嚭鍙ｆ敼涓?`ImageStatusSurface` 璺敱鈥斺€斿彲璇嗗埆鏂囨浠嶈蛋 `GuidanceNotice`锛屽叾浣欐敼璧拌亰澶╄緭鍏ユ爮鍚屾 `ComposerStatusMessage`锛屽苟瀵瑰叏鏂囧姞 `semantics { contentDescription }`锛岄伩鍏?2 琛屾埅鏂€犳垚鈥滄矇榛樷€濄€?- **F2** `ChatImagesWorkspaceEnqueue.kt` + `ChatImagesWorkspaceOverlay.kt`锛歚onGenerateImagePrompt(...) == false` 涓?`imageValidationError == null` 鏃跺啓鍏滃簳鏂囨 `鏃犳硶寮€濮嬪浘鐗囩敓鎴愶紝璇锋鏌ュ凡閫夋ā鍨嬩笌鐢熸垚璁剧疆銆俙锛堜笉瑕嗙洊 owner 鐨勭簿纭師鍥狅級銆?- **F3** `ImageGenerationOwner.kt`锛?5 澶勫噯鍏ユ棭閫€鍓嶅悇涓€鏉?`image_admit_reject code=<short> jobId=<id>` INFO 鏃ュ織锛堜笉鍚彁绀鸿瘝銆佹棤琛屼负鍙樺寲锛夈€?- 鏂板 `ImageStatusSurfaceGuardTest`锛? 渚嬶紝鍚袱澶勮皟鐢ㄧ偣鎺ョ嚎鏂█锛涙妸 F1 寮哄埗鏀瑰洖 GUIDANCE 浼?1/5 澶辫触銆乪xit 1锛屽凡鍙樺紓璇佹槑闈炴亽鐪熷苟鍥炴粴锛夈€?- **F4 鏈仛**锛坄ChatScreenActions` 鐨勯潤榛橀粯璁ゅ€艰鍏朵粬 lane 鐨勬祴璇曚緷璧栵級銆?
楠岃瘉锛堢湡瀹烇級锛?- `:feature:chat:testDebugUnitTest --rerun`锛?*65 suites / 491 tests / 0 failures / 0 errors / 0 skipped锛宔xit 0**锛?4s锛夈€?- `-PmcaDeviceTestVariant=true :app:assembleDeviceTest`锛欱UILD SUCCESSFUL 8m46s锛孉PK **565,813,309B sha256 `56dfee919e05611805263efeacf56e1595c37a26010b4fa1b539dadf7aeb5b29`**锛?8a 瀹夎浜?11:42:29锛堣澶囦晶 base.apk 鍝堝笇涓€鑷达紝鏇挎崲浜嗗閮?lane 鐨?`c2bdfec8鈥锛夈€?- **鐪熸満鍙鎬ц瘉鏄庯紙fixture 鏉′欢锛?*锛氬厛璇佹槑鈥滄棤妯″瀷鏃剁偣鐢熸垚鈥濊蛋鐨勬槸 `onOpenModels()` 绌烘€佸畧鍗紙涓嶆槸 submit銆佷篃涓嶆槸姝绘寜閽級锛涢殢鍚庢寜鎵瑰噯鍚?`shared_prefs/mca_local_image_models.xml` 绉嶅叆**涓€鏉＄己涓绘枃浠剁殑鍚堟垚璁板綍**锛堜笉鍒涘缓浠讳綍妯″瀷瀛楄妭锛夛紝鐢熶骇鍥剧墖椤电湡瀹炴覆鏌撳嚭 `鏈湴鍥惧儚鐢熸垚妯″瀷涓绘枃浠朵笉瀛樺湪锛岃閲嶆柊涓嬭浇鎴栭噸鏂板鍏ャ€俙鈥斺€旀埅鍥?+ uiautomator XML 鍚?TextView bounds銆乣鍏抽棴鎻愮ず` 鎸夐挳涓庢惡甯﹀叏鏂囩殑 a11y `content-desc`銆傝瘉鎹槑纭爣娉ㄤ负 鈥渇ixture-seeded condition, no real engine installed, UI-surfacing proof only鈥濄€?- 鍥炴粴锛歱refs 鍒犻櫎鍚庝笌 pre-state 瀹屽叏涓€鑷达紙涓嶅瓨鍦?0B锛夛紝`files/image_models` 鍓嶅悗閮戒笉瀛樺湪锛宍image_assets` 0 鏂囦欢 0 琛岋紝鍏朵綑 DB 琛ㄨ鏁颁笉鍙橈紙浠?`chat_messages 2鈫?` 鏉ヨ嚜鑱婂ぉ鎺т欢鍔ㄤ綔锛屽凡瑙ｉ噴锛夛紱post-rollback 鎴浘涓?pre-fix 甯冨眬涓€鑷达紙浠呯姸鎬佹爮鏃堕挓宸級銆?
**鏂板彂鐜帮紙闈炴湰娆℃敼鍔ㄥ紩璧凤紝寰呰窡杩涳級**锛氬崐娉ㄥ唽鐨勬湰鍦板浘鐗囪褰曡嫢 `family=SD15`锛屼細鍦ㄥ惎鍔ㄦ椂宕╂簝鈥斺€擿java.lang.IllegalArgumentException: UltraFix UI dimensions must expose a bounded executable range.`锛坄ChatModelChoice.<init>`锛宍ChatScreenHeadTypes.kt:220`锛岀粡 `ChatUiStateMapping.kt:236`锛夈€備换浣曡В鏋愬嚭 `supportsUltraFix=true` 涓?UltraFix 缁村害鏃犵晫鐨勮褰曢兘浼氳Е鍙戯紱鏈疆鐢?`family=CUSTOM` 瑙勯伩銆傚缓璁湪 `ChatUiStateMapping/ChatModelChoice` 鍋氭湁鐣屾牎楠屽苟闄嶇骇涓衡€滅己灏戠粍浠垛€濓紝鑰屼笉鏄穿婧冦€?

## 2026-10-28 v1-08 杩藉姞 4锛?80脳832脳9脳1 鍒ゅ畾瀹為獙 鈥斺€?浜у搧鍑犱綍鏄€滆В鐮?+ 鐪嬮棬鐙椻€濆弻绾︽潫锛屼笉鏄噰鏍风害鏉?
| 椤?| 鍊?|
| --- | --- |
| run / attempt / revision | `v-93001521-4550-46be-be40-051d4ad24e6e` / `v-0f2dcf6e-9b5c-4022-830b-c641e3b4d288` / `rev-c11a29452e9885d8` |
| 鍙傛暟 | **480脳832銆? 甯с€?6fps銆? 姝?*銆乪uler銆乧fg 6.0銆乫low 3.0銆乸rompt `red ball on blue table`锛堜笌 A/C 鍚?prompt sha锛?|
| 鏃堕棿绾?| QUEUED 12:40:09.120 鈫?TEXT_ENCODE 12:40:23.112 鈫?SAMPLE 12:42:50.772 鈫?**鍗曟閲囨牱鍦?12:52:03 瀹屾垚锛?53.2s = 9.2min锛屼粛鍦ㄧ湅闂ㄧ嫍鍐咃級** 鈫?涔嬪悗 stage 涓€鐩村仠鍦ㄢ€滈噰鏍蜂腑鈥濓紝**19.9 鍒嗛挓娌℃湁浠讳綍 DECODE 浜嬩欢** 鈫?13:11:57.607 搴旂敤鐪嬮棬鐙楁潃鎺?`:local_video`锛坄has died: prcp BTOP` + FGS cancel锛?|
| 缁堟€?| **INTERRUPTED / resourceState UNCONFIRMED / errorCode `INTERRUPTED_PROCESS_DEATH`**锛宒etail `瑙嗛 worker 闀挎椂闂存病鏈夐樁娈垫帹杩涳紝宸茶姹傞€€鍑鸿繘绋媊锛?*鏃?outputMediaId銆佹棤 media 琛屻€佹棤 MP4 鈫?鏃犳硶鍋氳涔夊垽瀹?*锛涙€诲閽?31.8min |
| 鍐呭瓨/鐑?| 宄板€?worker PSS **8,165,180KB**锛?.79GiB锛夛紱worker swap PSS 宄板€?~326MB锛涚郴缁?MemAvailable 9.5GB鈫?.06GB锛泂wap free ~12GB锛?*鏃?OS OOM/LMK**锛汳IUI 鍝ㄥ叺鍦?12:56:56/12:57:16 鎶?worker锛?.53GB vs 6GiB 鈥減ss abort threshold鈥濓級璁″叆 2/3 娆″苟鎻愮ず will be kill锛屼絾 StabilityLocalService 鎸夆€滄父鎴忚繘绋嬧€濊烦杩囷紱鐪熸鏉€杩涚▼鐨勬槸搴旂敤鐪嬮棬鐙椼€傚嘲鍊肩數姹?41.6掳C锛孴hermal 0銆?|
| 鍚庣画 H | 涓嶅悓 prompt 鐨勬潯浠朵緷璧栨鏌?**NOT_RUN**锛欸 娌℃湁浜у嚭濯掍綋锛屼笖 40 鍒嗛挓鏃堕棿鐩掑凡鍒?|

### 缁撹锛堟敼鍐?V1 鐡堕鍒ゆ柇锛?
1. **杩戝師鐢熷垎杈ㄧ巼鐨?DiT 閲囨牱鍦?CPU 涓婂叾瀹炲彲璺?*锛?80脳832脳9 鍗曟 **9.2 鍒嗛挓**锛堟鍓嶇敤 128虏 token 澶栨帹鐨?53鈥?0min/step 鏄庢樉楂樹及锛屽凡琚疄娴嬪彇浠ｏ級銆?2. **鐪熸鐨勭摱棰堟槸 CPU VAE 瑙ｇ爜 + 鐪嬮棬鐙?*锛?80脳832脳9 鐨勮В鐮侀樁娈靛凡缁?**>19.9 鍒嗛挓涓斾笉鎶ヤ换浣曡繘搴?*锛岃 20 鍒嗛挓鐪嬮棬鐙楀綋浣溾€滃崱姝烩€濇潃鎺夈€傛寜甯ф暟绾挎€т及绠楋紝33 甯цВ鐮?鈮?.7脳 鍗?**~74 鍒嗛挓**锛?3 甯у崟姝ラ噰鏍?鈮?53脳3.667 鈮?**33.8 鍒嗛挓**銆傚洜姝?**480脳832脳33脳1 鍦ㄥ綋鍓嶇湅闂ㄧ嫍涓嬭繛涓€姝ラ兘鍙戝竷涓嶅嚭鏉?*锛?0 姝?鈮?1 灏忔椂閲囨牱 + 澶氬皬鏃惰В鐮併€?3. **杩欐槸涓€涓彲瀹氫綅鐨勪骇鍝佺己闄凤紝涓嶅彧鏄€滄參鈥?*锛歚VideoWorkerPipeline` 鍙湪 native `generate_video` 杩斿洖鍚庢墠涓婃姤 DECODE锛坄:164-166`锛夛紝瑙ｇ爜鏈熼棿闆惰繘搴︿簨浠讹紱鐪嬮棬鐙楀彧璁?`onStage`锛坄LocalVideoWorkerAdapter.kt:248-255`锛夈€備簬鏄€滈暱瑙ｇ爜鈥濆拰鈥滅湡鍗℃鈥濇棤娉曞尯鍒嗐€傚€欓€夌獎淇锛堥渶鐙珛璇勫锛屼笉鑳介『鎵嬫敼棰勭畻锛夛細
   - (a) native 渚ф寜甯?鍒嗗潡鍥炶皟瑙ｇ爜杩涘害锛寃orker 杞彂 DECODE 闃舵杩涘害锛?   - (b) 鍒嗛樁娈甸绠楋細閲囨牱鐪嬮棬鐙椾笌瑙ｇ爜棰勭畻鍒嗙锛堝苟璁板綍姣忛樁娈典笂闄愶級锛?   - (c) 鍒嗗潡/鍒嗗抚瑙ｇ爜锛坱iled decode锛夐檷浣庡崟娆¤繛缁椂闂达紱
   - (d) 鎶?VAE 瑙ｇ爜鏀惧埌鍔犻€熷櫒锛堜笌 DiT 鍚屾牱鐨?NPU/GPU 璁锛夈€?4. **璇箟闂浠嶆湭鍒ゅ畾**锛氶渶瑕佷竴娆¤兘鍦ㄧ湅闂ㄧ嫍鍐呭畬鎴愮殑楂樺垎杈ㄧ巼鐗囨銆傛帹鑽愪笅涓€姝?**256脳256脳9脳1**锛堥噰鏍?鈮?鈥?min銆佽В鐮?鈮?鈥?min锛岄璁℃€?6鈥?min 鍙畬鎴愶級锛屾嬁鍒扮涓€娈碘€滆緝楂樺垎杈ㄧ巼 + 宸插彂甯冣€濈殑鐗囧瓙锛屽啀鍋氬悓 seed 涓嶅悓 prompt 鐨勬潯浠朵緷璧栨鏌ャ€?

## 2026-10-28 v1-08 杩藉姞 5锛?56脳256脳9脳1 瀹屾垚锛堣В鐮?10 鍒嗛挓锛屼粛鍦ㄧ湅闂ㄧ嫍鍐咃級

| 椤?| 鍊?|
| --- | --- |
| run / attempt / revision | `v-06ec5604-3b12-4160-99d5-762ba27e46b1` / `v-4c9d4491-b95c-46b6-ad5b-407a42defbc0` / `rev-47149c18f935b5cf` |
| 鍙傛暟 | 256脳256銆? 甯с€?6fps銆?*1 姝?*銆乸rompt `red ball on blue table`銆丩OCAL_NATIVE |
| 鏃堕棿绾?| QUEUED 13:38:31.191 鈫?TEXT_ENCODE 13:38:45.119锛?14.0s锛夆啋 SAMPLE 13:41:25.649锛?160.5s锛孴EXT_ENCODE+LOAD锛夆啋 鍗曟鍦ㄧ洃鎺?t+267s锛?.45min锛夊畬鎴?鈫?DECODE 13:55:51.651锛圫AMPLE鈫扗ECODE 鍏?866.0s锛屽嵆瑙ｇ爜 鈮?0min锛夆啋 ENCODE/PUBLISH 1.0s 鈫?COMPLETED 13:55:52.619 |
| 鑰楁椂 | 鎬?**1041.4s锛?7.4min锛?* |
| 璧勬簮 | 宄板€?worker PSS **8,896,776KB锛?.49GiB锛?*锛涘嘲鍊肩數姹?41.2掳C锛汿hermal 0 |
| 濯掍綋 | `media-b4fb0931鈥 **65,015B** sha256 `dd6527f728137b129e09d310836fb7d0bb73654d39e4af626a6bfe3d38816859`锛堜富鏈烘媺鍙栦笌 DB 涓€鑷达級锛沨264 256脳256 / 16fps / 9 甯?/ 0.5625s / 鏃犻煶杞?|
| 鎶藉抚 | 鍧囧寑鐏扮传鑹插櫔澹扮汗鐞嗭紝**鏃犱富浣?*锛? 姝ュ睘棰勬湡锛?|

**閲忓寲缁撹锛堣ˉ鍏ㄨВ鐮佺害鏉熺殑杈圭晫锛?*锛?
- 256脳256脳9 鐨?CPU 瑙ｇ爜 鈮?0min锛?*浠嶅湪 20min 鐪嬮棬鐙楀唴 鈫?鑳藉畬鎴愬苟鍙戝竷**锛?80脳832脳9 鐨勮В鐮?>19.9min 鈫?琚潃銆傛墍浠モ€滆В鐮?鐪嬮棬鐙椻€濈害鏉熺殑**瑙﹀彂鐐逛粙浜?256脳256脳9 涓?480脳832脳9 涔嬮棿**锛屼笉鏄墍鏈夊嚑浣曢兘涓嶅彲鐢ㄣ€?- 鍗曟鎴愭湰瀹炴祴锛?56脳256脳9 閲囨牱 4.45min锛?80脳832脳9 閲囨牱 9.2min锛?28脳128脳9 鐢熶骇閾?85.5s/姝ワ紙4 姝ュ潎鍊硷級銆傝繖浜涙槸鍚庣画鍋氶绠楄〃鐨勭洿鎺ラ敋鐐癸紝鏇夸唬涓€鍒囩嚎鎬у鎺ㄣ€?- 璇箟浠?**NOT_MET**锛? 姝ュ湪 64/128/256 閮芥槸鍣０鎴栬壊鍦猴紱64脳64脳20 姝ヤ粛鏃犱富浣撱€?*鍞竴杩樻病璇曡繃鐨勬槸鈥滆緝澶у嚑浣?+ 澶氭鈥?*锛?56脳256脳9脳4 鈮?1min锛岃В鐮佺害 10min锛屽悇闃舵閮借繙灏忎簬 20min 鐪嬮棬鐙?鈫?鍙互瀹屾垚锛夈€傝瀹為獙宸叉帓缁欒澶囦唬鐞嗭紝浣滀负鏈疆鏈€鍚庣殑璇箟鍒ゅ畾銆?

## 2026-10-28 v1-08 杩藉姞 6锛?56脳256脳9脳4 瀹屾垚锛堥涓?>64虏 鐨勫姝ユ垚鐗囷級鈥斺€旇涔変粛 NOT_MET

| 椤?| 鍊?|
| --- | --- |
| run / attempt / revision | `v-523ab465-8fca-4271-8a3a-a61ae1142843` / `v-d2061695鈥 / `rev-c58e3b5a鈥 |
| 鍙傛暟 | 256脳256銆? 甯с€?6fps銆?*4 姝?*銆乸rompt `red ball on blue table`銆乻eed -1799143745405931708銆丩OCAL_NATIVE |
| 鏃堕棿绾?| QUEUED 14:07:52.857 鈫?TEXT_ENCODE 14:08:07.955 鈫?SAMPLE 14:10:44.359 鈫?4/4 姝ュ畬鎴?14:18:21锛?*456.2s 閲囨牱 = 114.1s/姝?*锛夆啋 DECODE 14:31:37.409锛?*瑙ｇ爜 13.3min**锛夆啋 COMPLETED 14:31:38.536 |
| 鑰楁椂 | 鎬?**1425.7s锛?3.8min锛?*锛涢噰鏍蜂笌瑙ｇ爜閮藉悇鑷?<20min 鐪嬮棬鐙楀唴 鈫?鑳藉彂甯?|
| 璧勬簮 | 宄板€?worker PSS **8,609,948KB**锛涘嘲鍊肩數姹?41.7掳C锛堟彁浜ゅ墠鎸夐棬闄愬喎鍗村埌 38.2掳C锛夛紱Thermal 0 |
| 濯掍綋 | `media-5c4629f9鈥 **61,014B** sha256 `6412fc8d05950bb2f76c1b64527c36418094755d9002b2c1af9dbd7d71179793`锛沨264 256脳256 / 9 甯?/ 16fps / 鏃犻煶杞?|
| 鎶藉抚锛堜弗鏍硷級 | 棣?涓?鏈潎涓烘娊璞¤糠骞绘潯绾逛笌鑹插潡锛?*鏃犵孩鐞冦€佹棤妗屽瓙銆佹棤杩炶疮鐗╀綋 鈫?璇箟 NOT_MET** |

**缁撹鏇存柊**锛氭妸鍑犱綍浠?64虏 鎻愬埌 256虏銆佹鏁颁粠 1 鎻愬埌 4锛岃緭鍑轰粠鈥滆壊鍦衡€濆彉鎴愨€滄湁缁撴瀯鐨勬娊璞＄汗鐞嗏€濓紝浣?*浠嶄笉鍑虹幇鎻愮ず涓讳綋**銆傜粨鍚?I2V 鍦?1 姝ユ椂鑳藉鐜拌緭鍏ョ孩鐞冿紙璇佹槑 VAE 瑙ｇ爜涓?init-image 閫氳矾鍙敤锛夛紝褰撳墠鏈€鍙枒鐨勬槸**鏂囨湰鏉′欢鏄惁鐪熸杩涘叆 DiT**銆?
**涓嬩竴姝ワ紙鏈€楂樹环鍊笺€佹渶渚垮疁锛?*锛氬悓 seed銆佸悓鍑犱綍 64脳64脳9脳4锛屼袱涓埅鐒朵笉鍚岀殑 prompt 鍚勮窇涓€娆★紝姣旇緝 MP4 鍝堝笇涓庡抚宸細
- 涓ゆ瀹屽叏鐩稿悓 鈫?**鏂囨湰鏉′欢鏈繘鍏ユā鍨嬶紙鐪熷疄缂洪櫡锛?*锛?- 涓ゆ涓嶅悓 鈫?鏉′欢姝ｅ父锛岃涔夌己澶辨槸妯″瀷/鍒嗚鲸鐜囪兘鍔涢棶棰橈紙CPU 璺嚎鍙兘绠楀疄楠岃兘鍔涳級銆?

## 2026-10-28 v1-08 杩藉姞 7锛堝喅瀹氭€э級锛氭枃鏈潯浠舵病鏈夎繘鍏?DiT 鈥斺€?涓や釜瀹屽叏涓嶅悓 prompt 杈撳嚭鍍忕礌瀹屽叏鐩稿悓锛涘彟鍙戠幇 UI 绉嶅瓙缂洪櫡

### 1. 鏉′欢渚濊禆 A/B锛圥ROBE-ONLY / 闈炰骇鍝侀摼锛屼絾鐢ㄧ湡瀹?`VideoWorkerPipeline` + native `generateVideo`锛?
| 椤?| A | B |
| --- | --- | --- |
| prompt | `red ball on blue table` | `a blue sports car on a highway at night` |
| seed | **鍥哄畾 42**锛堟帰閽?`VideoNativeBudgetDeviceTest.kt:93` 纭紪鐮侊級 | 鍚?42 |
| 鍑犱綍/姝ユ暟 | 64脳64脳9脳4锛宔uler/cfg 6.0/flow 3.0 | 瀹屽叏鐩稿悓 |
| 缁撴灉 | Succeeded锛?,516B锛宻ha `b0bcaebc鈥锛屽嘲鍊?PSS 8,945,286KB | Succeeded锛?,516B锛宻ha `2add760d鈥锛屽嘲鍊?8,954,605KB |
| **甯у姣?* | frame0/4/8锛?*sha256 鐩稿悓锛宮eanAbsDiff = 0.000锛宮axAbsDiff = 0.0** | 鍚屼笂 |
| MP4 瀵规瘮 | 浠?12 涓瓧鑺備笉鍚岋紝鍏ㄩ儴浣嶄簬 `creation_time` 鍏冩暟鎹紱**mdat 杞借嵎鏃犲樊寮?* | 鈥?|

**鍒ゅ畾锛氬湪鍥哄畾 seed 涓嬫洿鎹?prompt锛岃緭鍑哄儚绱犲畬鍏ㄤ笉鍙?鈬?鏂囨湰鏉′欢娌℃湁褰卞搷 DiT銆傝繖鏄?V1 鐨勭湡瀹炵己闄凤紝涔熻В閲婁簡姝ゅ墠鎵€鏈夆€滄棤涓讳綋鈥濈粨鏋滐紙64虏/128虏/256虏銆?鈥?0 姝ラ兘涓€鏍凤級銆?* 璇佹嵁锛歚device-ab-ANALYSIS.txt`銆乣device-ab-{A,B}.mp4`銆乣device-ab-{A,B}-f{0,4,8}.png`銆乣device-ab-probe{A,B}-report.txt`銆?
璇氬疄杈圭晫锛氭湰娆¤蛋 opt-in headless 鎺㈤拡锛堢粫杩?owner/adapter/Service/gate锛夛紝浣嗗鐢ㄧ湡瀹?pipeline 涓?native锛涜鈥滃湪 UI 涓婅鏃ュ織纭鈥濋渶鍏堜慨涓嬮潰鐨勭瀛愮己闄凤紝鎴栧厛缁?native/worker 鍔犳潯浠惰瘖鏂棩蹇椼€傜粨璁哄鍚屼竴浠?native 浠ｇ爜鎴愮珛銆?
### 2. 鏂扮己闄凤細UI 鈥滅瀛愨€濆瓧娈靛湪棣栨鎻愪氦鏃惰蹇界暐

- UI 鎾锛氬嚑浣曢潰鏉?`绉嶅瓙` 杈撳叆 `20261028`锛坄device-run6-02-geometry-sheet-after.xml` 鍙瘉锛夛紝浣嗘彁浜ゅ悗 `params.seed = 4111864769242043674`锛堥殢鏈猴級銆?- 浠ｇ爜锛歚VideoGenerationOwner.kt:177-178` 鈥斺€?`seedPolicy = command.seedOverride?.let { Fixed(it) } ?: Randomized(store.nextSeed())`锛沗seedOverride` 鍙湪**閲嶈瘯**鏃舵彁渚涳紙绾?`:351`锛夈€?- 鍚庢灉锛氱瀛愬瓧娈靛彧鏀瑰彉 draft/revision 鎽樿锛?*涓嶆敼鍙樼湡姝ｄ娇鐢ㄧ殑 seed**锛涚敤鎴风湅鍒扮殑鏄€滃彲缂栬緫浣嗕笉鐢熸晥鈥濓紝涓旈噸璇曚笌闈為噸璇曡涔変笉涓€鑷淬€俇I 鎻愮ず鍐欑殑鏄€? 琛ㄧず鎻愪氦鏃堕殢鏈衡€濓紝鍗抽潪 0 鏈簲鍥哄畾銆?- 鏈€灏忎慨澶嶅缓璁細`submit()` 閲屾寜 UI 绾﹀畾瑙ｆ瀽 draft 鐨?sampler seed 鈥斺€?`command.seedOverride` 浼樺厛锛屽叾娆?draft seed 鈮?0 鐢?`Fixed(draftSeed)`锛屽惁鍒?`Randomized`锛涘苟鍔犲洖褰掓祴璇曪紙鍥哄畾 seed 涓ゆ鎻愪氦寰楀埌鐩稿悓 seed锛? 浠嶉殢鏈猴紱閲嶈瘯娌跨敤鍘?seed 鐨勬棦鏈夎涔変笉鍙橈級銆?- 褰卞搷锛氬湪淇鍓嶏紝浠讳綍鈥滃彈鎺?A/B / 鍙鐜伴噰鏍封€濋兘涓嶈兘閫氳繃鐢熶骇 UI 瀹屾垚锛堟湰娆″彧鑳藉€熸帰閽堬級銆?
### 3. 瀵规暣浣撶粨璁虹殑褰卞搷

- 璇箟闂ㄧ **NOT_MET** 鐨勬牴鍥犱粠鈥滄ā鍨?鍒嗚鲸鐜囪兘鍔涒€濆崌绾т负**鏄庣‘鐨勫疄鐜扮己闄凤紙鏉′欢閾撅級**锛涘湪淇骞堕€氳繃鏉′欢渚濊禆娴嬭瘯涔嬪墠锛屼笉搴斿仛鎬ц兘/棰勭畻鍐荤粨锛屼篃涓嶅簲鎹瀹ｇО妯″瀷涓嶅彲鐢ㄣ€?- 淇鍚庣殑涓嬩竴楠岃瘉椤哄簭锛氬悓 seed 涓?prompt 鍑虹幇鏄庢樉宸紓 鈫?鍐嶈窇 64脳64脳9脳20 / 128脳128脳9脳4 / 256脳256脳9脳4 鐪嬭涔?鈫?鍐嶈皥棰勭畻涓?NPU銆?

## 2026-10-28 v1-08 杩藉姞 8锛氫慨澶?UI 绉嶅瓙缂洪櫡锛堥娆℃彁浜や笉鍐嶈闅忔満瑕嗙洊锛?
**缂洪櫡**锛堣杩藉姞 7锛夛細`VideoGenerationOwner.submit()` 鍙湪 `command.seedOverride != null` 鏃剁敤鍥哄畾绉嶅瓙锛岃€?`seedOverride` 浠呴噸璇曟椂鎻愪緵锛屼簬鏄敓浜?UI 鐨勨€滅瀛愨€濆瓧娈碉紙鎻愮ず鍐欌€? 琛ㄧず鎻愪氦鏃堕殢鏈衡€濓紝闈?0 搴斿浐瀹氾級鍦ㄩ娆℃彁浜ゆ椂琚潤榛樺拷鐣ャ€?
**淇**锛坄app/src/main/java/com/muyuchat/mca/video/execution/VideoGenerationOwner.kt`锛夛細
- 鏂板 `draftSeedOf(document)`锛氫粠 `WAN_CONDITIONING` 鑺傜偣鐨?`seed` 鍙傛暟璇诲彇 draft 鍊笺€?- 绉嶅瓙瑁佸喅椤哄簭鏀逛负锛?*閲嶈瘯 override 鈫?draft seed锛堚墵0 鐢?`Fixed`锛夆啋 `Randomized(store.nextSeed())`**锛沝raft seed = 0 浠嶆寜 UI 绾﹀畾闅忔満銆傝涓哄彧琛ヤ笂 UI 宸插０鏄庣殑璇箟锛屼笉鏀瑰彉闅忔満璺緞涓庨噸璇曡矾寰勩€?- 娉ㄩ噴鏄庣‘璁板綍鈥滆瀛楁姝ゅ墠鏄楗版€х殑鈥濊繖涓€浜嬪疄锛岄伩鍏嶄笅娆¤璇垹銆?
**楠岃瘉**锛歚VideoGenerationOwnerTest` **15 tests / 0 failures / 0 errors / 0 skipped**锛堝師 13 + 鏂板 2锛夛紝exit 0锛?- `an explicit draft seed is used verbatim on submit` 鈥斺€?draft seed=424242 鎻愪氦鍚?`resolvedParams.seed == 424242`锛?- `a zero draft seed still randomises at submit` 鈥斺€?draft seed=0 鎻愪氦鍚?seed 鈮?0銆?璇佹嵁锛歚jvm-seed-fix.log`銆乣jvm-seed-fix-result.xml`銆?
**灏氭湭鍋氾紙闇€瑕佽澶囷級**锛氶噸缂?APK 鈫?瑁?device-a 鈫?鍦?*鐢熶骇 UI** 涓婄敤鍥哄畾绉嶅瓙閲嶅仛鈥滀袱涓笉鍚?prompt鈥濈殑 A/B锛屼互鍦ㄧ敓浜ч摼涓婄‘璁ゆ潯浠堕摼缂洪櫡锛堝綋鍓嶇粨璁烘潵鑷帰閽堣矾寰勶級銆傝繖涓€姝ユ帓鍦ㄦ潯浠堕摼淇涔嬪悗涓€璧峰仛锛岄伩鍏嶆氮璐硅澶囩獥鍙ｃ€?

## 2026-10-28 v1-09锛氭潯浠堕摼鏍瑰洜瀹氫綅 + 涓ゅ蹇呴』淇殑鐩插尯锛團IX-A/FIX-B 宸茶惤鐩橈級

### 1. 鏍瑰洜鍒嗘瀽缁撹锛堝彧璇伙紝24KB 鎶ュ憡 `build/evidence/v1-09/conditioning-rootcause.md`锛?
宸茶瘉浼笁涓€滃父瑙佺寽娴嬧€濓細
- **鈥減rompt 娌′紶鍒?native鈥濅笉鎴愮珛**锛歱robe payload 鈫?`VideoWorkerPipeline`锛堝師鏍峰瓧绗︿覆锛夆啋 JNI 鈫?`sd_video_generate.cpp:384 params.prompt` 鈫?`GenerationRequest.prompt` 鈫?`get_learned_condition(text=鈥?`锛坄stable-diffusion.cpp:7002`锛夛紱绌?prompt 浼氬湪 `:284` 鐩存帴 `INPUT_INVALID`锛屼袱娆￠兘鎴愬姛銆?- **鈥淭5 娌¤/闈欓粯闆舵潯浠垛€濅笉鎴愮珛**锛氭壘涓嶅埌 t5xxl 鏃?tokenize 杩斿洖绌?鈫?cond 绌?鈫?`context=nullptr` 鈫?`Linear::forward(nullptr)`锛堟棤 null 淇濇姢锛宍ggml_extend.hpp:2991-3004`锛変細**宕╂簝**鑰屼笉鏄潤榛橈紱涓ゆ鎴愬姛 鈬?缂栫爜鍣ㄧ‘瀹炶閰嶅苟杩愯銆?- **鈥滃唴宓?UMT5 璇嶈〃鍧忎簡鈥濅笉鎴愮珛**锛氫富鏈鸿В鐮?`src/tokenizers/vocab/umt5.hpp` 寰楀悎娉?`Metaspace`/float vocab銆?
**鐪熸闃诲瀹氫綅鐨勬槸鑷繁閫犳垚鐨勮娴嬬洸鍖?*锛?- **F-3锛堝繀椤讳慨锛?*锛歚sd_video_generate.cpp:353` 鐢?`sd_set_log_callback(nullptr,nullptr)` 鎶?sd.cpp 鍏ㄩ儴鏃ュ織鍏虫帀锛涘浘鍍忚矾寰勬湁 logcat 鍥炶皟锛岃棰戜晶鏈夌幇鎴?`log_line()` 鍗存病鐢ㄣ€倂1-07 鐨?6.9MB/109MB logcat 閲?`t5xxl|get_learned_condition|IMPORTANT NOTICE` 闆跺懡涓€?- **F-5锛堝繀椤讳慨锛?*锛氬揩鐓у彧瀛?`promptSha256`锛屾墽琛屼晶鍗翠粠鏂囨。鑺傜偣鍙栨枃鏈紝`effectiveMismatch` 浠庝笉鏍￠獙 prompt 鈬?鈥滄敼鎻愮ず璇嶄絾娌跨敤鏃ф枃妗?閲嶈瘯鈥濅細闈欓粯鐢ㄩ敊鏂囨湰骞惰褰曟纭搱甯屻€?
娈嬩綑鍊欓€夋牴鍥狅紙姣忔潯涓€娆″疄楠屽彲璇佷吉锛夛細**R1** 寮哄埗 `flash_attn/diffusion_flash_attn=true` 鍦?CPU 涓婁腑鍜屼氦鍙夋敞鎰忓姏锛堟渶楂樹紭鍏堢骇锛夛紱**R2** TI2V-5B 鐨?48 閫氶亾 in/out 鎺ョ嚎锛?*R3** `zero_out_masked` 鎶?cond 娓呴浂銆?
### 2. FIX-A锛氭妸 sd.cpp 鏃ュ織鎺ュ洖 logcat锛堝凡钀界洏銆佸凡缂栬瘧锛?
`core/sd-native/src/main/cpp/sd_video_generate.cpp`锛?- 鏂板 `sd_log_callback`锛堟寜 `sd_log_level_t` 鏄犲皠 ERROR/WARN/DEBUG/INFO 鈫?logcat锛宼ag 浠嶆槸 `McaVideoNative`锛屽墠缂€ `sd.cpp:`锛夛紝鏇挎崲鍘熸潵鐨?`sd_set_log_callback(nullptr,nullptr)`銆?- context 寤虹珛鍚庢柊澧炰袱琛岃瘉鎹棩蹇楋細妯″瀷鍚?+ `supports_video` + `distilled_guidance`锛涗互鍙?geometry/steps/seed/guidance/flow_shift + **prompt 鎸囩汗锛團NV-1a锛屼笉鎵撳師鏂囷級** + negative 鎸囩汗銆?- 缂栬瘧楠岃瘉锛歚:core:sd-native:assembleDebug` **BUILD SUCCESSFUL**锛?5s锛夛紱璇佹嵁 `build/evidence/v1-09/native-log-fix-build2.log`銆?- 灏氭湭鍋氳澶囬獙璇侊紙闇€閲嶇紪 APK + 瑙ｉ攣璁惧璺戜竴杞級鈥斺€斾絾杩欐鏄悗缁垽璇?R1/R2/R3 鐨勫墠鎻愩€?
### 3. FIX-B锛氭墽琛屽墠鏍￠獙鈥滄彁绀鸿瘝 = 蹇収鍝堝笇鈥濓紙宸茶惤鐩樸€佸凡娴嬭瘯锛?
`app/src/main/java/com/muyuchat/mca/video/worker/LocalVideoWorkerAdapter.kt`锛?- 鏂板 `internal fun promptMatchesSnapshot(prompt, expectedSha256)`锛堜笌 compiler 鐨?`sha256Of` 瀹屽叏鍚岀畻娉曪紱绌?pin 瑙嗕负鏈浐瀹氾級銆?- `runAttempt` 鍦ㄧ粍 payload 鍓嶆牎楠岋紝涓嶄竴鑷寸洿鎺?`VideoErrorCode.INPUT_INVALID` 鎷掔粷锛堜笉鏂板閿欒鐮侊紝鏈嶄粠鍐荤粨鐮佽〃锛夈€?- 鏂板 `PromptIntegrityTest`锛?*4/4 PASS**锛堢簿纭尮閰嶃€佺鏀规嫆缁濄€佸ぇ灏忓啓涓嶆晱鎰熴€佺┖ pin 鏀捐锛夛紱`VideoGenerationOwnerTest` 鍚屾 **15/15 PASS**锛堝惈绉嶅瓙淇涓ゆ潯锛夈€傝瘉鎹?`build/evidence/v1-09/jvm-prompt-integrity.log`銆乣jvm-prompt-integrity-xml/`銆?
### 4. NPU 渚х‖浜嬪疄锛堥『甯﹁褰曪紝`npu-toolchain-facts.md`锛?
QAIRT 2.45 鐨?4 涓?python 宸ュ叿鍦ㄦ湰鏈哄叏閮?exit=1锛屽師鍥犳槸 **ABI 涓嶅尮閰?*锛歚libPyIrGraph.pyd` 瀵煎叆 `python310.dll`锛屾湰鏈烘槸 3.12.10銆備慨娉曟槸瑁?**CPython 3.10 x86_64**锛堟垨璧?WSL Ubuntu 22.04 鑷甫 3.10锛夛紝**涓嶆槸**琛?pip 鍖呫€俹p 瑕嗙洊/纾佺洏/VAE 鍒囩墖浠?NOT_RUN銆?

## 2026-10-28 v1-09 杩藉姞锛歠lash-attn 璇婃柇寮€鍏?+ GGUF 寮犻噺鍚嶆牳瀵癸紙鎺掗櫎鈥滀氦鍙夋敞鎰忓姏鏉冮噸缂哄け鈥濓級

### 1. 璇婃柇寮€鍏筹紙涓嶆敼鍙樼敓浜ч粯璁わ級

- native锛歚sd_video_generate.cpp` 瑙ｆ瀽鍙€?`flashAttn`锛堥粯璁?true锛夛紝浼犵粰 `ctx_params.flash_attn` 涓?`diffusion_flash_attn`锛屽苟鍐欏叆璇佹嵁鏃ュ織 `flash_attn=鈥銆?*鐢熶骇 adapter 涓嶅彂閫佽閿?*锛岄粯璁よ涓轰笉鍙橈紱鍙湁 opt-in 鎺㈤拡锛坄VideoNativeBudgetDeviceTest`锛宍-e flashAttn false`锛変細鐢?false銆?- 鏋勫缓锛歚:core:sd-native:assembleDebug`銆乣:app:assembleDeviceTest`銆乣:app:assembleDeviceTestAndroidTest` 鍏ㄩ儴 **BUILD SUCCESSFUL**銆?- 浜х墿锛坄build/evidence/v1-09/apk-identity.txt`锛夛細base `431F8A47鈥锛?92,556,456B锛夈€乤ndroidTest `8E3E84D6鈥锛?,698,308B锛夈€?
### 2. DiT GGUF 寮犻噺鍚嶆牳瀵癸紙璁惧鏃犲叧锛屾帓闄や竴涓€欓€夋牴鍥狅級

鐢?HTTP Range 鍙?DiT Q4_0 GGUF 鍓?4MB锛坄dit-head-4mb.bin`锛夎В鏋?GGUF 澶达細**825 涓紶閲忋€?0 涓?block**锛宐lock0 鍚?`blocks.0.cross_attn.{q,k,v,o,norm_q,norm_k}.{weight,bias}` 涓?`self_attn`銆乣ffn.0/ffn.2`銆乣modulation`銆乣norm3`锛涘叏鏂囦欢 `cross` 鍛戒腑 **300** 涓紙30 block 脳 10 寮犻噺锛夈€?杩欎笌 `wan.hpp` 鐨?`blocks["cross_attn"]`锛坄WanT2VCrossAttention`锛宷/k/v/o/norm_q/norm_k锛夊懡鍚嶅畬鍏ㄤ竴鑷?鈬?**鈥淕GUF 缂哄皯浜ゅ弶娉ㄦ剰鍔涙潈閲嶅鑷?prompt 鏃犳晥鈥濅笉鎴愮珛**銆?
鍙︽牳瀵?`wan.hpp` 鐨?TI2V-5B 鍒嗘敮锛?0 灞?鈫?`dim=3072`銆乣ffn_dim=14336`銆乣in_dim=out_dim=48`銆乣text_len=512`锛屼笌鎴戜滑鐨?DiT GGUF锛?0 block銆?8 閫氶亾锛変竴鑷?鈬?R2鈥?8 閫氶亾鎺ョ嚎鈥濅篃鍩烘湰鎺掗櫎锛堣嫢鐗堟湰/閫氶亾鍒ゆ柇閿欎細褰㈢姸鎶ラ敊鑰岄潪闈欓粯锛夈€?
### 3. 浠嶅緟纭

- UMT5锛坈ity96 Q8_0锛塆GUF 鐨勫紶閲忓悕鍓嶇紑锛?MB 澶村彧瑕嗙洊鍒?tokenizer 璇嶈〃锛屾湭鍒?tensor info锛涘畬鏁磋В鏋愰渶鏇村ぇ Range 鎴栬澶囦晶鏃ュ織銆傝嫢鍓嶇紑涓嶆槸 `text_encoders.t5xxl.transformer.` 涓?loader 鏄犲皠澶辫触锛屽彲鑳藉嚭鐜?**T5 鏉冮噸鏈鍏ヨ€屽彧 WARN**锛坄stable-diffusion.cpp:804-807`锛夛紝闅忓悗 cond 鎭掑畾 鈫?涓庤娴嬪畬鍏ㄥ惢鍚堛€傝繖姝ｆ槸 FIX-A 鏃ュ織锛坄loading t5xxl from鈥 / tensor 璀﹀憡 / `get_learned_condition completed`锛夐鍏堣鍥炵瓟鐨勯棶棰樸€?- R1锛圕PU flash-attn 涓拰浜ゅ弶娉ㄦ剰鍔涳級涓?R3锛坈ond 娓呴浂锛夐潬鏂?APK 鐨?A/B 瀹為獙鍒ゅ畾锛坄-e flashAttn false` 瀵圭収锛夈€?

## 2026-10-28 v1-09 杩藉姞锛堝喅瀹氭€э紝鐪熸満鏃ュ織锛夛細prompt 琚垎鎴?**0 涓?token** 鈥斺€?鏉′欢閾炬牴鍥犵‘璁?
FIX-A 鐨?sd.cpp 鏃ュ織鍦ㄧ湡鏈虹涓€杞紙T1锛?4脳64脳9脳4锛宻eed 42锛宲rompt `red ball on blue table`锛宖lash_attn=true锛夊氨缁欏嚭浜嗙洿鎺ヨ瘉鎹紙`build/evidence/v1-09/device-diag-T1-logcat.txt`锛夛細

```
I McaVideoNative: sd.cpp: stable-diffusion.cpp:1803 - running in FLOW mode
I McaVideoNative: context model=Wan 2.2 TI2V supports_video=true distilled_guidance=false
I McaVideoNative: request geometry=64x64 frames=9 steps=4 seed=42 guidance=6.000000 flow_shift=3.000000 flash_attn=true prompt_fp=64b41d6d85d398a8-22 negative_fp=none
D McaVideoNative: sd.cpp: conditioner.hpp:1845 - parse 'red ball on blue table' to [['red ball on blue table', 1], ]
D McaVideoNative: sd.cpp: t5_unigram_tokenizer.cpp:336 - split prompt "red ball on blue table" to tokens []
D McaVideoNative: sd.cpp: ggml_extend.hpp:1902 - t5 compute buffer size: 297.00 MB(RAM)
D McaVideoNative: sd.cpp: conditioner.hpp:1938 - computing condition graph completed, taking 65722 ms
```

**缁撹锛堝凡鐢辩湡鏈烘棩蹇楄瘉鏄庯紝涓嶅啀鏄€欓€夛級**锛?1. 妯″瀷璇嗗埆姝ｇ‘锛坄Wan 2.2 TI2V`銆乣supports_video=true`銆侀潪 distilled guidance锛夛紝flash_attn 涓?true锛?2. prompt 纭疄鍒拌揪 sd.cpp 骞惰鍒囧潡锛坄parse ... to [[... ,1]]`锛夛紱
3. **浣?UMT5 tokenizer 鎶?prompt 鍒囨垚 0 涓?token**锛坄to tokens []`锛夛紝T5 浠嶈姳 65.7s 璁＄畻鈥滅┖搴忓垪鈥濈殑鏉′欢鍥撅紱
4. 鍥犳 cond 瀵规墍鏈?prompt 閮界瓑浠?鈫?杈撳嚭涓?prompt 鏃犲叧锛堜笌 A/B 鍍忕礌瀹屽叏鐩稿悓涓€鑷达級銆?*鈥滄枃鏈潯浠舵棤鏁堚€濈殑鏍瑰洜鏄?tokenizer 杩斿洖绌?token锛岃€屼笉鏄潯浠舵潈閲?浜ゅ弶娉ㄦ剰鍔?閲忓寲/鍒嗚鲸鐜囥€?*

鏃佽瘉锛氶敊璇?璐熼潰 prompt `''` 鍚屾牱寰楀埌 `tokens []`锛堝悓涓€鐜拌薄锛夈€?
### 宸叉帓闄ょ殑鍊欓€夛紙閬垮厤璇慨锛?
- **涓嶆槸** T5 鏈杞斤細鏃ュ織鏈?`t5 compute buffer size: 297.00 MB(RAM)` 涓旀潯浠跺浘璺戜簡 65.7s锛涜嫢 cond 涓虹┖浼氳蛋 nullptr 宕╂簝璺緞銆?- **涓嶆槸** 浜ゅ弶娉ㄦ剰鍔涙潈閲嶇己澶憋細DiT GGUF 825 寮犻噺銆?0 block锛宍blocks.N.cross_attn.{q,k,v,o,norm_q,norm_k}` 鍏?300 涓紝鍛藉悕涓?`wan.hpp` 鐨?`blocks["cross_attn"]` 涓€鑷淬€?- **涓嶆槸** 閫氶亾/鐗堟湰鎺ョ嚎锛歚wan.hpp` 鐨?TI2V-5B 鍒嗘敮锛?0 灞?鈫?dim 3072 / in/out 48 / text_len 512锛変笌 GGUF 涓€鑷淬€?- **涓嶆槸** 璇嶈〃 JSON 鎹熷潖锛氫富鏈鸿В鐮佸唴宓?UMT5 璇嶈〃 9,147,613B锛孞SON 鍙В鏋愶紝256,300 鏉°€乻core 鍏ㄤ负 float銆乣pre_tokenizer={Metaspace, 鈻? add_prefix_space:true}`銆?*涔熶笉鏄?* `text_encoders.t5xxl.transformer.` 鍓嶇紑涓嶅尮閰嶏細`name_conversion.cpp:105-170` 鐨?`t5_name_map` 鏄庣‘鎶?`enc.`/`blk.`/`attn_q.`/`token_embd.` 鏄犲皠鍒?diffusers 鍚嶏紝city96 鐨?`enc.blk.*` 鏂囦欢鏄?sd.cpp 鏀寔鐨勬牸寮忋€?
### 涓嬩竴姝ワ紙tokenizer 灞傦級

`T5UniGramTokenizer::EncodeOptimized` 鍦?`status() != OK` 鏃剁洿鎺ヨ繑鍥?`{}`锛坄t5_unigram_tokenizer.cpp:157`锛夛紝鑰?`BuildTrie` 浼氭妸 `INVLIAD_JSON / NO_PIECES_LOADED / BUILD_DOUBLE_ARRAY_FAILED / NO_ENTRY_FOUND` 涔嬩竴鍐欒繘 `status_`锛坄:88-124`锛夈€傚洜姝や笅涓€姝ユ槸鍦?submodule 鐨?tokenizer 鍒濆鍖栧悗鍔犱竴琛?`status_/pieces/trie_results_size_` 璇婃柇锛堢粡 FIX-A 鐨勬棩蹇楀洖璋冭緭鍑猴級锛屾垨鐩存帴鍦ㄤ富鏈虹紪璇戣 tokenizer 璺戜竴娆?`encode("鈻乺ed鈻乥all鈥?)` 澶嶇幇锛涘啀鎸夊叿浣?status 淇紙鏈€鍙兘鏄?Darts trie 鏋勫缓/鏌ユ壘闂锛屾垨 `InitializePieces` 鏈璋冪敤锛夈€?*杩欐槸褰撳墠鍞竴闃诲璇箟楠屾敹鐨勪唬鐮佺己闄凤紝浼樺厛绾ч珮浜庝竴鍒囨€ц兘/棰勭畻/NPU 宸ヤ綔銆?*

## 2026-10-28 v1-09 杩藉姞锛堟敹灏撅級锛氱浜屽璧勪骇涓嶅吋瀹?鈥斺€?DiT patch_embedding 5D 寮犻噺琚?GGUF reader 鎷掔粷

T1/T2 鍙?prompt 鏃ュ織锛坲ild/evidence/v1-09/device-diag-tokenizer-evidence.txt锛夐櫎纭 tokenizer 绌?token 澶栵紝杩樻毚闇茬浜屽**鐪熸満鍙**鐨勮祫浜т笉鍏煎锛?
```
sd.cpp: ... tensor 'patch_embedding.weight' has invalid number of dimensions: 5 > 4
sd.cpp: ... failed to read tensor info
```

Wan DiT 鐨?patch embedding 鏄?Conv3D锛? 缁存潈閲嶏級锛岃€?sd.cpp 鐨?GGUF reader 鍙帴鍙?鈮? 缁达紱璇ュ紶閲忚璺宠繃浣嗘ā鍨嬩粛鈥滃姞杞芥垚鍔熲€濆苟 supports_video=true 鈬?DiT 鐨?latent patchify 鏉冮噸缂哄け銆備袱澶勫彔鍔狅紙tokenizer 绌?+ patch_embedding 缂哄け锛夎鏄庯細**褰撳墠 QuantStack/city96 杩欎竴濂?ComfyUI 鍙栧悜鐨?GGUF 涓?sd.cpp 鐨勫姞杞藉櫒骞朵笉瀹屽叏鍏煎**锛屽嵆浣挎ā鍨嬭兘鍔犺浇銆佽兘鍑虹墖銆?
### 鏈疆鍚屾椂纭鐨勫叾瀹冧簨瀹?
- T1/T2锛歱rompt_fp 涓嶅悓锛?4b41d6d鈥?vs edaf325a鈥︼級浣嗕袱鑰呴兘 	o tokens []锛涙潯浠跺浘鍒嗗埆 65.7s/75.8s锛実et_learned_condition 132.6s/157.0s锛涘抚 0/4/8 sha256 瀹屽叏鐩稿悓銆乵eanAbsDiff 0.000銆?- Using flash attention 涓ゆ閮芥墦鍗?鈬?flash-attn 涓嶆槸鍘熷洜锛圱3/T4 宸茶烦杩囷級銆?- 64脳64脳9锛? 姝ラ噰鏍?107.45s銆乂AE 瑙ｇ爜 37.7s銆?- **DB 璁℃暟宸紓鏄?WAL 璇诲彇鍙ｅ緞闂锛屼笉鏄垹鏁版嵁**锛氭姤鍛婄湅鍒?30 runs/196 events/13 media 鈫?29/188/12锛岀己鐨勬鏄渶杩戜竴娆?UI run -82056a7e鈥?鍙婂叾 media锛涜 lane 鎷夊簱鏃跺彧鍙栦簡 mca.db 鏈彇 -wal銆?*鏁欒锛氳瘉鎹媺搴撳繀椤诲悓鏃跺彇 mca.db + mca.db-wal + mca.db-shm**锛涙湰浼氳瘽姝ゅ墠鐨?run4 璁℃暟灏辨槸甯?WAL 鎷夌殑銆?- 璁惧宸插鍘燂紙timeout 60000銆乻tayon off銆両ME銆乸roxy銆佹棤杩涚▼銆侀噸鏂伴攣灞忋€亊39.7掳C/thermal 0锛夈€?
### 淇鍚庣殑淇椤哄簭锛堝叧閿矾寰勶級

1. **璧勪骇鍏煎**锛氭妸 patch_embedding 绛?5D conv 鏉冮噸杞垚 sd.cpp 鎺ュ彈鐨?4D 鎺掑竷锛屽苟瑙ｅ喅 UMT5 tokenizer 绌?token锛堜簩閫変竴锛氫慨 tokenizer status锛屾垨鎹?sd.cpp 鍏煎鐨?UMT5 缂栫爜鍣ㄦ枃浠讹級銆傝繖涓ら」浠讳竴鏈В锛岃涔夐獙鏀堕兘涓嶅彲鑳介€氳繃銆?2. 淇ソ鍚庣敤鍥哄畾 seed 鍙?prompt A/B 澶嶉獙鏉′欢渚濊禆锛堢敓浜?UI + 鎺㈤拡鍚勪竴娆★級銆?3. 鍐嶉噸璺戣涔夌煩闃碉紙64虏脳20 / 128虏脳4 / 256虏脳4锛夛紝鐒跺悗鎵嶈皥棰勭畻鍐荤粨/鍔犻€?NPU銆?

## 2026-10-28 v1-09 杩藉姞锛坱okenizer 璇婃柇锛?
鍦?	hird_party/stable-diffusion.cpp/src/tokenizers/t5_unigram_tokenizer.cpp 鍒濆鍖栨湯灏惧姞涓€琛岃瘖鏂紙璧?FIX-A 鐨勬棩蹇楀洖璋冿紝璁惧涓嬩竴娆¤繍琛屽嵆鍙鍑哄け璐ユ€侊級锛?
```
LOG_DEBUG("MCA t5 tokenizer init: is_umt5=%d pieces=%zu status=%d trie_results=%d pairs=%zu", ...)
```

瀵瑰簲 EncodeOptimized 鍦?status() != OK 鏃剁洿鎺ヨ繑鍥?{}锛?157锛夛紝BuildTrie 浼氬啓鍏?INVLIAD_JSON / NO_PIECES_LOADED / BUILD_DOUBLE_ARRAY_FAILED / NO_ENTRY_FOUND锛?88-124锛夈€?
**涓昏鍋囪锛堝緟璇ユ棩蹇楃‘璁ゅ悗淇級**锛欱uildTrie 鐢?std::sort(pieces->begin(), pieces->end()) 瀵?(std::string, int) 鎺掑簭锛宻td::string 姣旇緝鎸?**signed char**锛汥arts 鍙屾暟缁勮姹傞敭鎸?**unsigned 瀛楄妭搴?*銆俇MT5 璇嶈〃鍚ぇ閲?鈻?E2 96 81) 绛夊瀛楄妭 piece锛屼袱绉嶅簭涓嶄竴鑷?鈬?	rie_->build() 杩斿洖闈?0 鈬?BUILD_DOUBLE_ARRAY_FAILED 鈬?encode 鎭掕繑鍥炵┖銆傝嫢鏃ュ織纭锛屾渶灏忎慨澶嶆槸鎸?unsigned 瀛楄妭搴忔帓搴忥紙涓€琛?comparator锛夛紝骞惰ˉ涓€鏉♀€滈潪 ASCII piece 鍙紪鐮佲€濈殑瀹堟姢娴嬭瘯銆?
缂栬瘧楠岃瘉锛?core:sd-native:assembleDebug **BUILD SUCCESSFUL**锛坲ild/evidence/v1-09/tokenizer-diag-build.log锛夈€傝澶囬獙璇侀渶涓嬩竴娆¤В閿侊紙閲嶇紪 APK 鈫?璺戜竴杞?鈫?璇?MCA t5 tokenizer init 琛岋級銆?

## 2026-10-28 v1-09 杩藉姞锛坱okenizer 淇牴鍥狅細Darts 閿簭锛?
璁惧鏃犲叧璇佹嵁锛坲ild/evidence/v1-09/sort-order-evidence.txt銆乧heck-sort-order.py锛夛細浠庡唴宓?UMT5 璇嶈〃瑙ｅ嚭 256,300 涓?piece锛屾寜 **std::string锛坰igned char锛夊簭** 涓?**Darts 瑕佹眰鐨?unsigned 瀛楄妭搴?* 鎺掑簭锛?*256,298/256,300 涓綅缃笉鍚?*锛堥涓樊寮傜储寮?2锛歕x01 vs \x80锛夈€侭uildTrie 鐢?std::sort(pieces->begin(), pieces->end())锛坰td::pair<std::string,int>锛夋帓搴忥紝涓?Darts 鐨?strcmp/unsigned 搴忎笉涓€鑷?鈬?	rie_->build() 鍙嫆缁濅贡搴忛敭 鈬?status_=BUILD_DOUBLE_ARRAY_FAILED 鈬?EncodeOptimized 杩斿洖 {} 鈬?**prompt 鍙?0 token**锛堜笌鐪熸満鏃ュ織涓€鑷达級銆?
**淇**锛?hird_party/stable-diffusion.cpp/src/tokenizers/t5_unigram_tokenizer.cpp 鐨?BuildTrie 鏀逛负鎸?unsigned 瀛楄妭搴忕殑 lexicographical_compare 鎺掑簭锛堥檮鍘熷洜娉ㄩ噴锛夈€?core:sd-native:assembleDebug **BUILD SUCCESSFUL**锛?okenizer-sortfix-build.log锛夈€傚悓鏃朵繚鐣欎簡 MCA t5 tokenizer init: 鈥?status=鈥?璇婃柇锛岃澶囦笅涓€娆¤繍琛屽彲鐩存帴楠岃瘉 status=0 涓?split prompt 鈥?to tokens [...] 闈炵┖銆?
**娉ㄦ剰**锛氳繖鏄瀛愭ā鍧楁簮鐮佺殑琛ヤ竵锛堜笌鍏朵粬鏃㈡湁 native 琛ヤ竵鍚屽睘鏋勫缓杈撳叆锛夛紝闇€鍦?HANDOFF 鐨勬瀯寤鸿緭鍏ユ竻鍗曚腑鐧昏锛涜澶囬獙璇侊紙閲嶇紪 APK + 璺戜竴杞?+ 鍙?prompt A/B锛夊緟涓嬩竴娆¤В閿併€?

## 2026-10-28 v1-09 杩藉姞锛坧atch_embedding 缁村害纭锛?
浠?DiT GGUF 澶磋В鏋愶紙uild/evidence/v1-09/patch-dims-evidence.txt銆乧heck-patch-dims.py锛夛細

```
patch_embedding.weight : dims = [2, 2, 1, 48, 3072]   鈫?5 缁达紙Conv3D kernel (2,2,1) 脳 in48 脳 out3072锛?patch_embedding.bias   : dims = [3072]
```

ggml 寮犻噺鏈€澶?4 缁达紝sd.cpp 鐨?GGUF reader 鎷掔粷 5 缁达紙鐪熸満鏃ュ織 invalid number of dimensions: 5 > 4 + ailed to read tensor info锛夛紝鍥犳 **patch_embedding 鏉冮噸琚暣浣撹烦杩?*锛孌iT 鐨?latent patchify 鏃犳潈閲嶃€傝繖涓?tokenizer 绌?token 鏄袱澶勭嫭绔嬬殑璧勪骇涓嶅吋瀹癸紱浠讳綍鈥滆涔夊彲杈ㄨ瘑鈥濋獙鏀跺湪涓よ€呴兘淇ソ鍓嶄笉鍙兘閫氳繃銆?
淇鏂瑰悜锛堜笅涓€杞級锛氱‘璁?sd.cpp 鐨?Wan patchify 鏈熸湜鐨?4D 鎺掑竷锛堟姌 [2,2,1]鈫抂4] 鎴?[48,3072,2,2]锛夛紝鎹鍐?GGUF 閲嶅啓鍣ㄦ妸璇ュ紶閲忚浆鎴?4D锛堟暟鎹瓧鑺備笉鍙樸€佷粎鏀?dims/offset锛夛紝鎴栧湪 sd.cpp loader 鍐呭仛绛変环鎶樺彔锛涗袱鑰呴兘闇€璁惧 A/B 澶嶉獙銆?

## 2026-10-28 v1-10锛歵okenizer 淇鏈楠岃瘉閫氳繃锛坰tatus=5 = INVLIAD_JSON锛屾棭閫€浜庤瘝琛ㄥ姞杞藉墠锛?
鐪熸満楠岃瘉锛堟柊 APK base 649DB859鈥︼紝瑁?device-a 17:05:17锛屾帰閽?64脳64脳9脳4 seed 42锛夛細

```
MCA t5 tokenizer init: is_umt5=1 pieces=0 status=5 trie_results=0 pairs=0
split prompt "red ball on blue table" to tokens []      锛堢┖璐熷悜鍚屾牱 []锛?get_learned_condition completed, taking 137.53s          锛圱5 浠嶅湪绌哄簭鍒椾笂璺?69.29+68.24s锛?patch_embedding.weight ... 5 > 4                          锛堢浜屽缂洪櫡浠嶅湪锛?```

status=5 = **INVLIAD_JSON**锛宲ieces=0 / pairs=0 璇存槑 InitializePieces() 鍦?*鍔犺浇绗竴涓瘝琛ㄩ」涔嬪墠**灏辫繑鍥炰簡鈥斺€斿嵆鍥涗釜鏃╂湡 INVLIAD_JSON 鍒嗘敮涔嬩竴锛圝SON parse 澶辫触 / 鏃?model / 鏃?ocab / 棣栭」绫诲瀷涓嶇锛夛紝**涓庢垜涓婁竴杞€€鐤戝苟淇鐨?Darts 閿簭鏃犲叧**锛堥敭搴忎慨澶嶆湰韬粛鐒舵纭紝鍙槸涓嶆槸鏈棁鐘剁殑鏍瑰洜锛夈€?
涓绘満渚у凡纭鍐呭祵 umt5.hpp 鐨勫瓧鑺傛槸鍚堟硶 JSON锛?,147,613B銆乯son.loads 鎴愬姛銆乵odel.type=Unigram銆?56,300 鏉°€乻core 鍏?float銆乸re_tokenizer={Metaspace, 鈻? add_prefix_space:true}锛夆噿 鏁版嵁娌￠棶棰橈紝**杩愯鏃舵棭閫€鏄?C++ 渚?*锛氭渶鍙兘鏄?9.1MB blob 杞?std::string 鐨勮竟鐣岋紙鏃?NUL / 闀垮害鎴柇锛夋垨 nlohmann parse 澶辫触銆?
**涓嬩竴姝ワ紙绮剧‘锛?*锛氬湪 InitializePieces 鐨勫洓涓?INVLIAD_JSON 杩斿洖鍓嶅悇鍔犱竴鏉℃棩蹇?+ json_str.size()锛堢粡 FIX-A 鍥炶皟杈撳嚭锛夛紝閲嶇紪鍚庡啀璺戜竴杞嵆鍙畾浣嶏紱涓嶈鍦ㄤ笅涓€娆¤繍琛屽墠鍐嶅姩 trie 鎺掑簭銆俻atch_embedding 5D 缂洪櫡浠嶅緟淇紙涓庢湰鏉＄嫭绔嬶級銆?

## 2026-10-28 v1-10锛堟牴鍥犵‘璁ゅ苟淇锛夛細Android 涓?UMT5 璇嶈〃琚紪璇戞帓闄わ紝load_umt5_tokenizer_json() 杩斿洖绌轰覆

闈欐€佸畾浣嶏紙	hird_party/stable-diffusion.cpp/src/tokenizers/vocab/vocab.cpp锛夛細

```cpp
std::string load_umt5_tokenizer_json() {
#if defined(__ANDROID__)
    return {};                    // 鈫?Android 涓婄洿鎺ヨ繑鍥炵┖涓?#else
    ... umt5_tokenizer_json_str ...
#endif
}
```

涓?umt5.hpp 鐨?include 涔熻鏀惧湪 #if !defined(__ANDROID__) 鍧楅噷銆備簬鏄?Android 涓?InitializePieces("") 鐨?
lohmann::json::parse("") 鎶涘紓甯?鈫?status_ = INVLIAD_JSON(5) 鈫?pieces=0 / pairs=0 鈫?EncodeOptimized 杩斿洖 {} 鈫?**姣忎釜 prompt 閮借鍒囨垚 0 涓?token**銆傝繖涓庣湡鏈烘棩蹇?status=5銆乸ieces=0銆乸airs=0銆?okens [] **閫愰」鍚诲悎**锛涗篃瑙ｉ噴浜嗕负浣?T5锛圫D3/Flux 鐢級鍦?Android 姝ｅ父鑰?UMT5锛圵an 鐢級鎭掔┖鈥斺€擳5 鐨?loader 娌℃湁璇?Android 鍒嗘敮銆?
**淇**锛?- ocab.cpp 鐨?load_umt5_tokenizer_json() 鍘绘帀 #if defined(__ANDROID__) return {} 鍒嗘敮锛孉ndroid 涓婁篃杩斿洖鍐呭祵璇嶈〃锛?- 鎶?#include "umt5.hpp" 绉诲嚭 #if !defined(__ANDROID__)锛堝惁鍒欑鍙蜂笉鍙锛夛紱
- 淇濈暀 BuildTrie 鐨?unsigned 瀛楄妭搴忔帓搴忎慨澶嶏紙瀹冩湰韬纭紝鍙槸涓嶆槸鏈棁鐘剁殑鏍瑰洜锛変笌 MCA t5 tokenizer init 璇婃柇銆?- 缂栬瘧锛?core:sd-native:assembleDebug **BUILD SUCCESSFUL**锛?m07s锛寀ild/evidence/v1-10/umt5-embed-build2.log锛涢娆″皾璇曞洜 include 缂哄け澶辫触锛寀mt5-embed-build.log 宸蹭繚鐣欙級銆?
**寰呰澶囬獙璇?*锛堜笅涓€姝ワ紝绾?20 鍒嗛挓锛夛細閲嶇紪 APK 鈫?瀹夎 鈫?鎺㈤拡璺?64脳64脳9脳4 鈫?鏈熸湜 status=0銆乻plit prompt 鈥?to tokens [N>0]銆佷袱涓笉鍚?prompt 杈撳嚭鍑虹幇瀹炶川宸紓銆俻atch_embedding 5D 缂洪櫡浠嶇嫭绔嬪緟淇€?

## 2026-10-28 v1-10 鍐冲畾鎬?A/B锛氭潯浠堕摼宸蹭慨澶嶅苟鐢熸晥锛堥涓鍚戣涔変俊鍙凤級

鍚屼竴璁惧/APK锛坆ase 43179156鈥︼紝17:37:25 瀹夎锛夛紝鎺㈤拡 64脳64脳9脳4銆佸浐瀹?seed 42锛屼袱涓笉鍚?prompt锛?
| | T1 ed ball on blue table | T2  blue sports car on a highway at night |
|---|---|---|
| tokenizer | status=0 pieces=256300 trie_results=13 | 鍚?|
| tokens | **5**锛堚杹red 鈻乥all 鈻乷n 鈻乥lue 鈻乼able锛?| **9**锛堚杹a 鈻乥lue 鈻乻ports 鈻乧ar 鈻乷n 鈻乤 鈻乭ighway 鈻乤t 鈻乶ight锛?|
| 鏉′欢鑰楁椂 | 137.79s | 200.89s |
| 鎬昏€楁椂/宄板€?PSS | 328.1s / 7,285,410KB | 433.9s / 7,862,402KB |
| media | 8,718B sha 42cee4d3鈥?| 8,056B sha d2276679鈥?|

**甯у姣旓紙0/4/8锛?*锛氫笁甯?sha 鍏ㄤ笉鍚岋紝meanAbsDiff **24.29 / 28.64 / 40.37**锛坢ax 154/168/161锛夛紝MP4 鍐呭宸紓 7,889B锛堥潪鍏冩暟鎹級锛宖fmpeg PSNR 骞冲潎 **19.96 dB**銆傗噿 **鏉′欢閾惧凡鐪熸褰卞搷 DiT 杈撳嚭**锛屾鍓嶁€滀袱涓?prompt 鍍忕礌瀹屽叏鐩稿悓鈥濈殑鐜拌薄娑堝け銆?
缁撹涓庤竟鐣岋細
- 淇鏈夋晥锛歭oad_umt5_tokenizer_json() 鍦?Android 杩斿洖绌轰覆鏄敮涓€鏍瑰洜锛泃okenizer 鎺掑簭淇锛坲nsigned 搴忥級璁?	rie_results=13 姝ｅ父銆?- **浠嶄笉鏄骇鍝侀€氳繃**锛?4脳64脳9脳4 涓嬬敾闈粛鏄娊璞¤壊甯︼紙T1 钃濇潯绾?鍦板钩绾匡紝T2 钃濈传澶滆壊鏉＄汗锛夛紝鏃犳槑纭孩鐞?璺戣溅锛涜繖绗﹀悎鈥滃皬鍑犱綍+4 姝モ€濈殑鑳藉姏闄愬埗銆?- **绗簩澶勭己闄蜂粛鏈慨**锛氫袱杞棩蹇楅兘鍑虹幇 patch_embedding.weight ... 5 > 4锛圙GUF reader 鎷掔粷 5D Conv3D 鏉冮噸锛夈€傛澶栨棩蹇楀嚭鐜?diffusion_model 10.30MB 鐨勫紓甯稿皬鍙傛暟鍐呭瓨锛岄渶鏍告煡 DiT 鏉冮噸鏄惁鍙鍏ヤ簡灏戦儴鍒嗐€?- 璁惧鏃堕挓寮傚父锛圫un Nov 1 01:4x EST锛夊凡璁板綍锛岃鏃剁敤鎺㈤拡鐩稿鏃堕棿銆?
涓嬩竴姝ワ細淇?patch_embedding 5D鈫?D锛堟垨 loader 鎶樺彔锛夛紝鏍告煡 DiT 鏉冮噸瑁呰浇缁熻锛涚劧鍚庣敤鐢熶骇 UI 澶嶈窇 64脳64脳9脳20 涓?128脳128脳9脳4 鐪嬭涔夋槸鍚︽敼鍠勶紱鍐嶅畾棰勭畻銆?

## 2026-10-28 v1-10 杩藉姞锛歱atch_embedding 鏄叏搴撳敮涓€ 5D 寮犻噺锛屼笖浣嶄簬寮犻噺琛ㄦ湯灏?
涓绘満瑙ｆ瀽 DiT GGUF 寮犻噺琛紙uild/evidence/v1-10/tensor-order-evidence.txt锛夛細

```
total tensors: 825
patch_embedding.weight -> index 814 of 825 (tensors after it: 10)
patch_embedding.bias   -> index 813 of 825 (tensors after it: 11)
tensors with >4 dims: ['patch_embedding.weight']     鈫?鍏ㄥ簱鍞竴
```

鍚箟锛?- 5D 寮犻噺鍙湁 patch_embedding.weight 涓€涓紙[2,2,1,48,3072]锛夛紝瀹冧笉鏄?DiT 鐨勪富浣擄紱鍗充娇 reader 鍦ㄨ椤瑰涓锛屾渶澶氫篃鍙奖鍝嶅叾鍚庣殑 10 涓紶閲忥紙	ime_embedding.*銆?ime_projection.*锛夈€?- 鍥犳鈥淒iT 鏉冮噸鏁翠綋鏈姞杞解€濅笉鎴愮珛锛涗箣鍓嶆棩蹇楅噷鐨?diffusion_model 10.30MB 闇€瑕佺敤鏇村畬鏁寸殑鍔犺浇缁熻澶嶆牳锛堝彲鑳芥槸鍙︿竴涓皬 context 鎴栦粎缁熻宸插疄渚嬪寲寮犻噺锛夛紝涓嶈鎹鏂畾 3GB 鏉冮噸鏈杞姐€?- 浣?patchify 鏉冮噸缂哄け鏈韩瓒充互璁?DiT 杈撳叆閿欎贡 鈫?鐢婚潰鍛堟娊璞℃潯绾癸紝涓?A/B 瑙傛劅涓€鑷淬€?
淇鏂瑰悜锛堜笅杞級锛歱atch_embedding.weight 鐨?kernel 鏄?(2,2,1)锛坘d=1锛夛紝鍙妸 5D 鎶樺彔涓?4D [2,2,48,3072]锛堜涪寮?kd=1 缁达級骞剁浉搴旇 Wan patchify 璧?Conv2d 璺緞锛涘厛鍦?sd.cpp 鐨?GGUF reader 渚у仛鍚屾瀯鎶樺彔锛堜笉鏀?GGUF 鏂囦欢锛夛紝鍐嶅仛涓€娆℃帰閽?A/B 鐪嬬敾闈㈡槸鍚﹀嚭鐜板彲杈ㄨ瘑涓讳綋銆?

## 2026-10-28 v1-10 杩藉姞锛?D 寮犻噺涓嶆槸鈥滆烦杩団€濊€屾槸**涓鏁翠釜寮犻噺琛ㄦ壂鎻?*

闂浠ｇ爜鍦?ggml 鐨?GGUF reader锛?hird_party/stable-diffusion.cpp/ggml/src/gguf.cpp:611锛夛細

```cpp
if (n_dims > GGML_MAX_DIMS) {
    GGML_LOG_ERROR("%s: tensor '%s' has invalid number of dimensions: %" PRIu32 " > %" PRIu32, ...);
    ok = false;
    break;                 // 鈫?鐩存帴璺冲嚭鏁翠釜 tensor-info 寰幆锛屽悗缁紶閲忓叏閮ㄤ涪澶?}
```

鍥犳 patch_embedding.weight锛堢储寮?814/825锛変箣鍚庣殑閭?10 涓紶閲忥紙	ime_embedding.0/2銆?ime_projection.1 绛夛級閮戒笉浼氳璇诲埌锛涜繖涔熻В閲婁簡鏃ュ織閲?diffusion_model 鍙傛暟缁熻鍋忓皬鐨勪竴閮ㄥ垎銆?*淇蹇呴』鍦ㄨ繖涓?reader 閲屽仛鈥滄姌鍙犫€濊€屼笉鏄粎瀹归敊**锛氬綋 
_dims == 5 涓旂 3 缁翠负 1锛堟湰鏂囦欢姝ｆ槸 [2,2,1,48,3072]锛夋椂锛岃鍙?5 缁村悗鎶樺彔涓?4 缁?[2,2,48,3072]锛坘d=1 鐨?Conv3D 绛変环 Conv2D锛夛紝鍐嶇户缁壂鎻忓悗缁紶閲忋€?
**鏆備笉钀藉湴**锛氳繖鏄?loader 灞傜殑浣庡眰鏀瑰姩锛屽繀椤婚厤鍚堢湡鏈烘帰閽堥獙璇侊紙褰撳墠璁惧宸查攣灞忥級锛涘湪鏃犳硶楠岃瘉鐨勬儏鍐典笅鏀瑰畠椋庨櫓楂樹簬鏀剁泭銆備笅涓€杞殑椤哄簭搴斾负锛氳В閿?鈫?鍏堝仛涓€娆″彧璇绘帰閽堢‘璁?	ime_embedding 寮犻噺纭疄缂哄け锛堟棩蹇楅噷 grep 	ime_embedding 鍔犺浇鍛婅锛夆啋 鍐嶈惤鍦版姌鍙?鈫?A/B 澶嶉獙鏄惁鍑虹幇鍙鲸璇嗕富浣撱€?

## 2026-10-28 v1-10 杩藉姞锛堢湡鏈哄彇璇侊級锛氬熬閮?11 涓?DiT 寮犻噺纭疄缂哄け锛屽惈 	ext_embedding

鍙鎺㈤拡锛?4脳64脳9脳1锛宲rompt x锛?85.9s锛宮edia e88153c5鈥︼級纭锛?
- GGUF reader 鍦?**T814 patch_embedding.weight**锛坣d=5锛宒ims 2,2,1,48,3072锛孎32锛夊 reak锛?- **T815鈥揟824 杩?10 鏉?tensor info 浠庢湭琚鍙?*锛屽洜姝ょ己澶?**11 涓紶閲?*锛歱atch_embedding.weight銆?ext_embedding.0/2锛? 涓級銆?ime_embedding.0/2锛? 涓級銆?ime_projection.1锛? 涓級锛?- 杩愯鏃跺彧鏈?invalid number of dimensions: 5 > 4 + ailed to read tensor info 涓ゆ潯閿欒锛岄殢鍚?memory-mapped 620 tensors + loading tensors completed鈥斺€?*缂哄け鏄潤榛樼殑锛屾病鏈?per-tensor 璀﹀憡**锛?- tokenizer 鏃犲叧锛坰tatus=0銆乼okens ["鈻亁"]锛夈€?
### 瀵光€滄潯浠堕摼宸茬敓鏁堚€濈殑淇琛ㄨ堪

	ext_embedding.0/2 鏄?Wan DiT 鎶?T5 鏉′欢鎶曞奖杩涙ā鍨嬬淮搴︾殑灞傘€傚畠鑷繁缂哄け 鈬?**鏉′欢鏃犳硶鍦ㄨ涔夊眰闈㈣繘鍏?DiT**锛汚/B 閲屼袱涓?prompt 杈撳嚭涓嶅悓锛屾槸鍥犱负涓嶅悓鐨?cond 寮犻噺鍠傝繘鈥滅己鏉冮噸/闅忔満鏉冮噸鈥濈殑鍚屼竴璺緞锛屼骇鐢熶簡涓嶅悓浣嗘棤璇箟鐨勫浘妗堛€傚洜姝ゅ噯纭粨璁烘槸锛?*tokenizer 淇璁╂潯浠跺紶閲忔纭敓鎴愬苟鎶佃揪 DiT 鍏ュ彛锛堟鍓嶆亽涓虹┖锛夛紝浣?DiT 渚х殑鏉′欢/鏃堕棿鎶曞奖灞傜己鏉冮噸锛岃涔変粛涓嶅彲杈?*鈥斺€旇繖涓庘€?4脳64 涓嬪彧鏈夋娊璞℃潯绾广€佹病鏈夌孩鐞?璺戣溅鈥濆畬鍏ㄤ竴鑷淬€?
### 涓嬩竴澶勪慨澶嶏紙鏄庣‘銆佸崟鐐癸級

鍦?ggml/src/gguf.cpp 鐨勫紶閲忕淮搴﹁В鏋愬鍋氭姌鍙狅細褰?
_dims == GGML_MAX_DIMS + 1 涓斿瓨鍦ㄦ伆濂戒竴涓瓑浜?1 鐨勭淮搴︼紙鏈枃浠舵槸 [2,2,1,48,3072] 鐨勭 3 缁达級鏃讹紝璇诲叆 5 缁村悗涓㈠純璇?singleton 缁淬€佹寜 4 缁?[2,2,48,3072] 缁х画锛?*涓嶈 reak**锛涜繖鏍?T815鈥揟824 浼氳缁х画璇诲彇锛?ext_embedding/time_embedding/time_projection 鎭㈠銆傛敼瀹屽繀椤荤湡鏈?A/B锛氭湡鏈涗笉浠呭儚绱犱笉鍚岋紝鑰屼笖鍑虹幇鍙鲸璇嗕富浣擄紙绾㈢悆/璺戣溅锛夈€?
## 2026-10-28 v1-10 杩藉姞锛?D 鎶樺彔瀹為獙涓庡洖婊氾紙閲嶈鏁欒锛?
鎸夎鍒掑湪 `ggml/src/gguf.cpp` 瀹炵幇鈥?D 涓旀伆鏈変竴涓?singleton 缁?鈫?鎶樺彔涓?4D鈥濆悗锛岀湡鏈洪獙璇佺粨鏋滐細

- 鉁?鎶樺彔鐢熸晥锛氱湡鏈烘棩蹇?`tensor 'patch_embedding.weight' folded 5D shape (2, 2, 1, 48, 3072) -> (2, 2, 48, 3072)`锛沗invalid number of dimensions` 閿欒娑堝け锛汿815鈥揟824 寰椾互缁х画璇诲彇銆?- 鉂?浣?`patch_embedding_channels` 鐢?**147456 鍙樻垚 3072**锛宻d.cpp 鍥犳鎶?DiT 璇垽涓?**Wan2.1-T2V-1.3B**锛堝簲涓?Wan2.2-TI2V-5B锛夛紱闅忓悗 `blocks.0.cross_attn.k.weight has wrong shape: got [3072, 3072, 1, 1], expected [1536, 1536, 1, 1]`锛坄norm_q` / `norm_k` / `k.bias` 鍚屾牱锛夆啋 `load tensors from file failed` 鈫?**MODEL_LOAD_FAILED锛?9.4 s锛屾棤濯掍綋杈撳嚭**銆?- 鏋勫缓锛歚:core:sd-native:assembleDebug` + app/test锛孊UILD SUCCESSFUL **3m31s**锛沚ase APK `C108FB25鈥锛?*573,409,641 B**锛夛紝2026-10-28 18:45:46 瀹夎銆?- 鍥炴粴锛氬け璐ュ悗宸蹭粠澶囦唤 `E:\MAC\.dsh-tmp\gguf.cpp.v110e.bak` **閫愬瓧鑺傚洖婊?*锛涘綋鍓?`gguf.cpp` sha256 `2A48FF5B8CDFC468CDAFDE2AD618EF6322A23E2E64BE88E3E7C86DBA5B40129F`锛堝師濮嬪€硷級銆傛湰鏂囦欢涔嬪鏈敼鍔ㄤ换浣曟簮鐮併€?
**缁撹**锛歐an 鍙樹綋鎺㈡祴鍣ㄦ槸浠?patch-embedding 鐨勫舰鐘?閫氶亾鏁版帹鏂増鏈殑锛坄model.cpp:541` + `wan.hpp` 鐨勭増鏈垎鏀級锛屾墍浠?*鍙湪 reader 閲屾姌鍙?5D 涓嶅**銆備笅涓€杞繀椤诲悓鏃讹細
1. 璁╂帰娴嬮€昏緫浠嶈兘鐪嬪埌 5D 鐨勯€氶亾/鏍镐俊鎭紙渚嬪鎶?folded 鍓嶇殑 raw dims 涓€骞朵紶缁?detector锛夛紱鎴?2. 鍦ㄦ姌鍙犲悗淇 `patch_embedding_channels` 鐨勬帹瀵硷紝浣?5D `[2,2,1,48,3072]` 涓?4D `[2,2,48,3072]` 寰楀埌鐩稿悓鐨?Wan2.2-TI2V-5B 鍒ゅ畾銆?
**鎭㈠**锛氬洖婊氬悗鐨勯噸寤烘渶鍒濊 Gradle daemon 浜夌敤鍗′綇锛坄--rerun-tasks` 20 鍒嗛挓鏃犱骇鍑猴級锛涙敼鐢?`--no-daemon` 骞?touch 婧愭枃浠跺己鍒?CMake 閲嶇紪鍚庯紝浜у嚭鍙敤 base APK `81CB1B31鈥锛?*603,931,880 B**锛?026-10-28 19:42:48 瀹夎锛夈€傜煭鎺㈤拡锛?4脳64脳9脳1锛宲rompt `x`锛?*OK**锛?16 s锛宼okenizer `status=0`锛宍tokens ["鈻亁"]`锛宮edia 7,644 B sha256 `ddb189a530dda93c427ec43045be86faa254d7bb3e87ff4d5ff60d805e44b8ee`锛?*鏃?MODEL_LOAD_FAILED**锛沗patch_embedding.weight ... 5 > 4` 浠嶆寜棰勬湡鍑虹幇銆傛瀯寤鸿繃绋嬩腑涓€涓?`:app:bundleDeviceTestClassesToCompileJar` 浠诲姟鍥犵郴缁熻祫婧愪笉瓒冲け璐ワ紙APK 宸蹭骇鍑哄苟楠岃瘉鍙敤锛夛紝鍚庣画鏋勫缓缁熶竴鍔?`--no-daemon` 骞堕伩鍏嶄笌鍏朵粬 Gradle 鏋勫缓骞跺彂銆?

## 2026-10-28 v1-11锛氭帰閽堚€滄寕璧封€濇牴鍥?= MIUI Greeze 鍐荤粨锛堥潪鏈粨搴撶己闄凤級

绾跨▼鏍堝彇璇侊紙uild/evidence/v1-11/device-v111-hang-report.md銆乨evice-v111-hang-stacks.txt锛夛細

- 闃诲绾跨▼锛欴efaultDispatcher-worker-1 鍋滃湪 NativeCrypto.EVP_DigestUpdate 鈫?MessageDigest.update 鈫?**VideoModelAssetVerifier.sha256Of(VideoModelAssetVerifier.kt:86)** 鈫?VideoModelAssetVerifier.verify(:51) 鈫?VideoWorkerPipeline.execute锛涚害 2.1s CPU 鍚庡仠鍦?rt::ConditionVariable::WaitHoldingLocks / CheckJNI::ReleasePrimitiveArrayElements銆?- 鍚屾湡绯荤粺鏃ュ織锛欸reezeManager: ActionExecute: frozen uid = 10451 reason=check binder銆乻etUidState 鈥?allow=false 姣?~5s 閲嶅锛涘彂 SIGQUIT 鏃跺嚭鐜?THAW uid=10451 鈥?reason: Signal other caller銆?- JUnit 渚э細Time-limited test 鍦?VideoNativeBudgetDeviceTest:147 绛夌閬擄紱Instr: AndroidJUnitRunner 闃诲鍦?FailOnTimeout.getResult銆?
缁撹锛歩nstrumentation 杩涚▼娌℃湁鍙 Activity锛岃 MIUI 鐪佺數鍐荤粨绛栫暐锛圙reeze锛夊湪 JNI 閲屽弽澶嶅喕缁擄紝瀵艰嚧 3GB 妯″瀷鏂囦欢鐨?SHA-256 鏍￠獙姘歌繙璺戜笉瀹?鈬?鎺㈤拡鎸傝捣銆?*涓嶆槸 verifier 閫昏緫缂洪櫡銆佷笉鏄?gguf 鍫嗗彔鏀瑰姩銆佷笉鏄?Room/gate 姝婚攣**锛?9:43 閭ｆ鏈懡涓睘浜庣姸鎬佺浉鍏炽€?
澶勭疆涓庡缓璁細
1. 澶嶈窇鍓嶇疆鏉′欢锛氬睆骞曞父浜?瑙ｉ攣锛屽苟鎶婃湰搴旂敤鍔犲叆 MIUI 鐪佺數/鍐荤粨鐧藉悕鍗曪紝鎴?*璁╀竴涓墠鍙?Activity 淇濇寔瀛樻椿**鍐嶈捣 instrumentation锛?2. 浜у搧渚у姞鍥猴紙搴旂撼鍏ュ疄鐜帮級锛氬ぇ鏂囦欢鏍￠獙涓嶅簲鍦ㄦ棤鍓嶅彴/鏃犲敜閱掗攣鐨勮繘绋嬮噷瑁歌窇鈥斺€斿簲鏀惧湪甯﹀墠鍙版湇鍔＄殑楠岃瘉娴佺▼涓紝鎴栧湪宸叉湁鍙椾俊 manifest 鏃惰烦杩囬噸澶嶅搱甯岋紙褰撳墠 measuredPresets 浠嶄负绌猴紝姝ら」闇€璇勫鍚庡啀鍋氾紝涓嶅緱闈欓粯鏀惧鏍￠獙锛夈€?

## 2026-10-28 v1-11 鏀跺熬锛氬伐浣滄爲鏀舵暃锛堝爢鍙犺ˉ涓佹挙鍑恒€佸疄鐜颁繚鐣欍€丄PK 閲嶅缓锛?
- 鍫嗗彔瀹炵幇锛坓gml/src/gguf.cpp 鎶?鈮? 缁村爢鍙犺繘 
e[3]锛夊凡淇濆瓨涓?E:\MAC\.dsh-tmp\gguf.cpp.v111-stack.cpp锛坰ha 25916FC1鈥︼級锛岄殢鏃跺彲閲嶆斁锛?- 宸ヤ綔鏍?gguf.cpp 宸查€愬瓧鑺傛仮澶嶄负鍘熷 sha 2A48FF5B鈥?鈥斺€?涓嶅啀璁╂湭楠岃瘉鐨?native 鏀瑰姩鐣欏湪鍏变韩鏍戜腑褰卞搷鍏朵粬 lane 鐨勬瀯寤猴紱
- 鐢ㄦ仮澶嶅悗鐨勬爲閲嶅缓鍙敤 APK锛欱UILD SUCCESSFUL in 1m53s锛?-no-daemon锛屾棩蹇?uild/evidence/v1-11/restore-build.log锛夈€?- 鎵嬫満涓婁粛瑁呮湁鏈獙璇佺殑鍫嗗彔鐗?APK锛?88920BA鈥︼級锛涗笅涓€娆¤澶囩獥鍙ｇ涓€姝ュ簲鏄畨瑁呮湰娆￠噸寤虹殑鍥炴粴鐗堝苟璺戜竴娆?64脳64脳9脳1 鍐掔儫锛岀‘璁よ棰戝彲鐢ㄥ悗鍐嶉噸鍚爢鍙犻獙璇併€?
**缁忛獙锛堝姟蹇呮部鐢級**锛?1. 鏃ュ織鎹曡幏涓嶈鐢?Start-Process -RedirectStandardOutput锛堟湰鏈哄瓨鍦?NO_PROXY/
o_proxy 閲嶅鐜鍙橀噺浼氳瀹冪洿鎺ュけ璐ャ€佷骇鍑?0 瀛楄妭锛夛紝鏀圭敤 cmd /c "adb 鈥?> file"锛?2. MIUI Greeze 浼氬湪 instrumentation 鏃犲彲瑙佺獥鍙ｆ椂鍐荤粨杩涚▼锛?rozen uid = 鈥?reason=check binder锛夛紝闃诲鐐瑰湪妯″瀷鏂囦欢 SHA-256 鐨?JNI digest锛涜閬块渶鏄惧紡鎷夎捣涓荤晫闈?+ 杩愯鏈熼棿瀹氭椂 input tap锛屾垨鍦ㄩ獙璇佹祦绋嬩腑浣跨敤鍞ら啋閿?鍓嶅彴鏈嶅姟锛?3. 浠讳綍 native 浣庡眰鏀瑰姩蹇呴』鍦ㄩ獙璇侀€氳繃鍚庢墠鐣欏湪鏍戦噷锛屽け璐ュ嵆鍥炴粴骞堕噸寤哄彲鐢?APK銆?
## 2026-10-29 v1-12锛?8a 鎹㈡満楠岃瘉锛堝爢鍙犱慨澶嶇敓鏁堬級

device-a 閿佸睆瀵艰嚧楠岃瘉鍙楅樆鍚庯紝鍒囨崲鍒扮敤鎴锋壒鍑嗙殑 device-b锛圶iaomi 25091RP04C锛孲M8750P / Android 17锛孲DK 37锛夛紱device-a 涓?SM8550 / Android 16锛屽洜姝ゆ湰娆℃槸璺ㄦ満鍨嬶紙璺ㄨ姱鐗?璺ㄧ郴缁燂級楠岃瘉銆?
- 绌洪棿锛氱敤鎴锋槑纭€夋嫨娓呯悊 /data/local/tmp锛堚€淏鈥濇柟妗堬級锛屾竻鐞嗗悗 /data 鍙敤绾?16.2 GiB锛涗笅杞?9,982.85 MB 涓変欢濂楀悗鍓╀綑绾?6.4 GiB锛屾湭鍒犻櫎鍏朵粬搴旂敤鏁版嵁銆?- 涓嬭浇锛氶€氳繃搴旂敤鈥滆棰戔€濋〉 UI 瀹屾垚锛堥潪澶栭儴涓嬭浇宸ュ叿锛夛紝涓変欢濂楀ぇ灏忎笌 SHA-256 鍏ㄩ儴閫氳繃搴旂敤鍐呮牎楠岋細
  - dit/Wan2.2-TI2V-5B-Q4_0.gguf 3,029,086,560 B sha256 fcf40dd62cb5e15556eef5f975700ab0b071aacd1939607689276e88f81ba463
  - text_encoder/umt5-xxl-encoder-Q8_0.gguf 6,043,068,256 B sha256 2521d4de0bf9e1cc6549866463ceae85e4ec3239bc6063f7488810be39033bbc
  - vae/Wan2.2_VAE.safetensors 1,409,400,960 B sha256 e40321bd36b9709991dae2530eb4ac303dd168276980d3e9bc4b6e2b75fed156
- 鍏抽敭鏃ュ織锛?4脳64脳9脳1锛宲rompt x锛屽爢鍙犱慨澶嶇増锛夛細
  - tensor 'patch_embedding.weight' stacked 5D shape (2, 2, 1, 48, 3072) -> (2, 2, 1, 147456)锛堜笉鍐嶅嚭鐜?invalid number of dimensions锛?  - context model=Wan 2.2 TI2V supports_video=true distilled_guidance=false
  - memory-mapped 620 tensors in 3 files (9982.85 MB)
  - MCA t5 tokenizer init: is_umt5=1 pieces=256300 status=0 trie_results=13 pairs=256300
  - 杩愯鎴愬姛锛歡enerate_video completed in 144.71s锛涘獟浣?7,644 B sha256 3c518503039cd03b2d1140d7883b4be30132594a5dc147028d834d63cd6c216d
- A/B锛?4脳64脳9脳4锛宻eed 42锛屼繚鎸佸簲鐢ㄥ墠鍙颁互閬垮紑 MIUI Greeze 鍐荤粨锛夛細
  - T1 red ball on blue table锛?,718 B sha256 feb055b165e8d276f06e64e0ab6981ff6b35fce434245460eabaf3d3fd3e47f7锛?42.9 s
  - T2 a blue sports car on a highway at night锛?,056 B sha256 b64195b27c06017aeb8dc4e03e7b4b39bef4899f4b47f58b74416df8580abd69锛?45.5 s
  - 甯?0/4/8 鐨?sha 鍏ㄩ儴涓嶅悓锛沵eanAbsDiff 24.291 / 28.642 / 40.373锛孭SNR 骞冲潎 19.962 dB锛坹 19.67锛寀 23.79锛寁 18.80锛?  - 涓?device-a 涓婂悓 prompt/seed 鐨?A/B 鏁板€奸€愪綅涓€鑷达紙24.291/28.642/40.373銆?9.962 dB锛夛細璇?CPU 璺緞鍦ㄨ妯″瀷涓?seed 涓嬫槸纭畾鎬х殑锛岃法璁惧鍍忕礌灞傞潰涓€鑷?  - 瑙嗚鍒ゅ畾锛堣瘹瀹烇級锛氫袱娈甸兘鍙湁钃?绱壊鎶借薄鏉＄汗涓庢í鍚戣壊甯︼紝鐪嬩笉鍒板彲杈ㄨ瘑鐨勭孩鐞冩垨姹借溅鈥斺€斿儚绱犲凡闅?prompt 鍙樺寲锛屼絾璇箟灞傞潰浠嶆湭鍑虹幇涓讳綋
- 620 寮犻噺婢勬竻锛歮emory-mapped 620 tensors in 3 files (9982.85 MB) 缁熻鐨勬槸浠庝笁浠芥枃浠剁洿鎺?mmap 鐨勯噺鍖栨潈閲嶏紱total params memory size = 10.62MB锛坉iffusion_model 10.30MB锛夋槸闈?mmap 鐨?params backend buffer锛?25 涓紶閲忓钩鍧囩害 12.5 KB锛岄噺绾т笌 F32 norm/bias 涓€鑷达級锛屼袱鑰呭害閲忓璞′笉鍚岋紝骞朵笉鐭涚浘銆倀ext_embedding/time_embedding/time_projection 鍦?sd.cpp 涓病鏈夐€愬紶閲忔棩蹇楋紱鏈鏈嚭鐜?wrong shape銆乴oad tensors from file failed 鎴?MODEL_LOAD_FAILED锛屼笖妯″瀷浠?Wan 2.2 TI2V 姝ｅ父杩愯锛岃鏄?T815鈥揟824 宸查殢鍫嗗彔璇诲彇杩涘叆鍔犺浇璺緞鈥斺€斾絾鈥滃凡鍔犺浇鈥濈殑缁撹鏉ヨ嚜鈥滄棤閿欒 + 妯″瀷鍙繍琛屸€濓紝涓嶆槸鏉ヨ嚜姝ｅ悜閫愬紶閲忔棩蹇椼€?- 缁撹锛氬爢鍙犱慨澶嶅湪 device-b 涓婄敓鏁堬紙device-a 涓婄殑 MODEL_LOAD_FAILED 涓?Wan2.1 璇垽娑堝け锛夛紝涓斾负璺ㄨ澶囬獙璇侊紙SM8750P/Android 17 瀵?SM8550/Android 16锛夈€傝涔夐棬浠嶆湭閫氳繃锛?4脳64脳4 姝ュ彧鑳藉緱鍒版娊璞℃潯绾广€?8a 鐨?9,982.85 MB 妯″瀷鍖呬繚鐣欎负绗簩涓凡楠岃瘉璧勪骇浣嶇疆锛屾湭鍒犻櫎銆?

## 2026-10-29 鍙傝€冮」鐩鏍囷細nightmare-mobile锛圓ndroid + Hexagon NPU 鐨?Wan 2.2锛?
鐢ㄦ埛鎻愪緵鍙傝€冨疄鐜?https://github.com/AbrahamPaulJ/nightmare-mobile锛堣妭鐐瑰浘寮?Android 鐢熸垚鍣紝璺?Qualcomm Hexagon NPU锛夈€備笌鏈」鐩洿鎺ョ浉鍏崇殑涓夋潯纭簨瀹烇紙鏉ヨ嚜鍏?README锛夛細

1. **Wan 2.2 璧?NPU**锛歍ext and image to video: Wan 2.2, 5 绉掑壀杈?832脳480锛屽湪 8 Elite 涓?< 3 鍒嗛挓锛涙敮鎸佷换鎰忓舰鐘惰嚦 1280脳704銆?鈥?s锛涘ぇ鐗囨**鎸?tile 瑙ｇ爜**鎵嶈兘鍦?12GB 鎵嬫満涓婅窇 704脳1280銆?2. **闈犺捀棣忔鏁版彁閫?*锛歐an 2.2 14B 浣跨敤 **lightx2v 鐨?4-step 钂搁妯″瀷**锛堢害 9 鍒嗛挓/2s@480脳832銆?2 鍒嗛挓/1s@720脳1280锛夛紱5B 鏀寔 LoRA 閫夋嫨鍣ㄣ€?3. **妯″瀷鍦?NPU 涓婁互閲忓寲褰㈠紡杩愯**锛堝叾鐢熸€佺敤 npuforge 杞崲銆丵4_0/Q8_0 GGUF 鎴?int8 safetensors 鍠?QNN锛夛紝CPU 鍙綔閫€璺細鍏舵枃妗ｆ槑纭啓閬撴煇浜涜姱鐗団€淣PU 浠庢湭璺戝畬涓€涓?DiT step锛?12脳512 瑕?15鈥?0 鍒嗛挓鈥濄€?
**瀵规湰椤圭洰鐨勫惈涔夛紙閲嶈锛?*锛?- 鎴戜滑褰撳墠 CPU GGML 璺緞锛?80脳832脳33脳1锛氬崟姝?553s銆乂AE 瑙ｇ爜 19.9min 琚湅闂ㄧ嫍缁堟锛?*鍦ㄦ暟閲忕骇涓婁笉鍙兘杈惧埌浜у搧棰勭畻**锛屼笌鍙傝€冨疄鐜扮殑 <3min/5s 鐩稿樊绾?30鈥?0脳锛?- 浜у搧璺嚎蹇呴』绛変簬鈥?*QNN/HTP 鎵ц + 钂搁 4-step 鏉冮噸 + tile 瑙ｇ爜**鈥濊繖涓€缁勫悎锛汣PU 璺緞鍙兘瀹氫綅涓哄疄楠岃兘鍔涳紙涓?HANDOFF 鏃㈡湁缁撹涓€鑷达紝浣嗗弬鑰冨疄鐜扮粰鍑轰簡鍙揪鎴愮殑鏁板€肩洰鏍囷級锛?- 鍥犳鎵ц璁″垝搴旇皟鏁翠负锛氣憼鎶?NPU 閫氶亾浠庘€滃彟璁?2.5鈥? 鍛ㄢ€濇彁鍓嶄负浜у搧棰勭畻鐨勫敮涓€鍙璺緞锛涒憽璧勪骇闇€瑕佸鍔?**lightx2v 4-step 钂搁 LoRA/鏉冮噸**锛堝綋鍓?pin 鐨勪笁浠跺鍙湁 base 妯″瀷锛夛紱鈶㈣В鐮佸繀椤诲仛 tile 鍖栵紙鎴戜滑鐩墠鐨?VAE 瑙ｇ爜鏄竴娆℃€у叏甯у紶閲忥紝姝ｆ槸 19.9min 鐡堕涔嬩竴锛夈€?

## 2026-10-29 瑙ｇ爜鐡堕鐨勫叿浣撹В娉曪細涓婃父宸叉敮鎸?VAE tiling锛屾垜浠殑 wrapper 浠庢湭鍚敤

浠ｇ爜浜嬪疄锛?hird_party/stable-diffusion.cpp锛夛細
- include/stable-diffusion.h 瀹氫箟 sd_tiling_params_t { 鍚敤浣? temporal_tiling, tile_size_x, tile_size_y, target_overlap, rel_size_x, rel_size_y, extra_tiling_args }锛岄粯璁?{false,false,0,0,0.5f,0,0,nullptr}锛坰table-diffusion.cpp:617锛夈€?- generate_video 浼氭妸 params->vae_tiling_params 鐩存帴浜ょ粰 SD 涓婁笅鏂囷紙stable-diffusion.cpp:7251 闄勮繎鐨?sd_ctx->sd->vae_tiling_params = sd_vid_gen_params->vae_tiling_params;锛夛紝瑙ｇ爜渚ф湁 set_temporal_tiling_enabled / tiled decode 璺緞锛?2400銆?2404銆?2411銆?2415锛夈€?
鈬?**480脳832脳9脳1 鐨?VAE 瑙ｇ爜 19.9 鍒嗛挓骞堕潪鏃犺В**锛氫笂娓告湰鏉ュ氨鏀寔绌洪棿/鏃堕棿鍒嗗潡瑙ｇ爜锛屽彧鏄垜浠殑 sd_video_generate.cpp 浠庢湭璁剧疆 ae_tiling_params锛堜繚鎸侀粯璁ゅ叧闂級銆?涓嬩竴澶勪慨澶嶏紙璁惧鏃犲叧銆佸彲鍏堝疄鐜帮級锛氬湪 wrapper 閲屾毚闇插彲閫?aeTiling/tile 灏哄锛堥粯璁ゅ叧闂互涓嶆敼鍙樻棦鏈夎涓猴級锛屾帰閽堜笌鐢熶骇鍙樉寮忓紑鍚紝鐒跺悗鐢?480脳832脳9脳1 澶嶆祴瑙ｇ爜鑰楁椂涓?INTERRUPTED_PROCESS_DEATH 鏄惁娑堝け锛涘紑鍚悗蹇呴』璁板綍璐ㄩ噺宸紓锛堝垎鍧楄В鐮佸彲鑳芥湁鎺ョ紳锛夛紝涓嶅緱闈欓粯鎶婂畠璁句负榛樿銆?

## 2026-10-29 v1-13锛歏AE 鍒嗗潡瑙ｇ爜寮€鍏冲凡钀藉湴锛坵rapper 灞傦紝榛樿鍏抽棴锛?
core/sd-native/src/main/cpp/sd_video_generate.cpp 鏂板鍙€?payload 閿細aeTiling锛坆ool锛岄粯璁?false锛夈€?ileSizeX / 	ileSizeY锛坕nt锛夈€?ileOverlap锛坉ouble锛岄粯璁?0.5锛夛紝鍐欏叆 params.vae_tiling_params.{enabled,temporal_tiling,tile_size_x,tile_size_y,target_overlap,rel_size_*,extra_tiling_args}锛屽苟鎶?ae_tiling=鈥?鎵撹繘璇佹嵁鏃ュ織琛屻€?
- **榛樿鍏抽棴**锛氱敓浜?adapter 涓嶅彂杩欎簺閿紝鏃㈡湁琛屼负涓庤緭鍑轰笉鍙橈紙涓嶉潤榛樻敼瑙勬牸锛夈€?- 鐩殑锛氶獙璇佲€?80脳832脳9脳1 鐨?VAE 瑙ｇ爜 19.9 鍒嗛挓琚?20 鍒嗛挓鐪嬮棬鐙楃粓姝⑩€濇槸鍚﹀彲閫氳繃鍒嗗潡瑙ｇ爜瑙ｅ喅锛堜笌鍙傝€冮」鐩?nightmare-mobile 鐨勨€渂ig clips are decoded in tiles鈥濅竴鑷达級銆?- 缂栬瘧锛?core:sd-native:assembleDebug **BUILD SUCCESSFUL**锛?91s锛?uild/evidence/v1-13/vae-tiling-build2.log锛涘墠涓ゆ澶辫触鍒嗗埆鏄?here-string 鎶?"" 鍐欒繘 C++ 鐨勫紩鍙烽敊璇紝宸蹭慨锛夈€?- 寰呰澶囬獙璇侊細鎺㈤拡甯?-e 鏃犳鍙傛暟鑳藉姏锛岄渶鍦ㄦ帰閽堥噷鍔犲彲閫夐敭鎴栧湪 adapter 渚т复鏃跺紑鍚紱楠岃瘉鏃堕』鍚屾椂璁板綍瑙ｇ爜鑰楁椂涓庡垎鍧楁帴缂濊川閲忓樊寮傘€?
## 2026-10-29 v1-13锛歏AE 鍒嗗潡瑙ｇ爜瀵圭収瀹炴祴

涓洪獙璇?VAE 鍒嗗潡鑳藉惁瑙ｅ喅 480脳832 瑙ｇ爜鍋滄粸锛屾墿灞曚簡鎺㈤拡 payload锛歚vaeTiling`/`tileSizeX`/`tileSizeY`/`tileOverlap` 浠呭湪 instrumentation 鍙傛暟鎻愪緵鏃跺啓鍏ワ紙榛樿涓嶅彉锛夛紝骞跺啓鍏?start report 璁板綍 tiling 妯″紡锛涢噸缂?androidTest APK锛坄--no-daemon --max-workers=2`锛孊UILD SUCCESSFUL 5m50s锛宼est sha 7EDA8A13鈥︼級锛沚ase 淇濇寔 4AD057E1鈥︺€俷ative 渚х‘瀹炴敹鍒伴敭锛氭棩蹇楀嚭鐜?`vae_tiling=off`銆乣vae_tiling=512x288@0.500000`銆乣vae_tiling=32x32@0.500000`銆?
- 480脳832脳9脳1锛?*鏃犳硶娴嬪嚭缁撹**銆俠aseline 璺?2 娆°€乼iled 璺?1 娆★紝鍧囧湪 sampling/decode 瀹屾垚鍓嶈**鍏跺畠 DSH lane 鐨?adb 寮哄埗鍋滆繘绋?*鏉€姝伙紙鏃ュ織 `am force-stop com.muyuchat.mca.devicetest`銆乣Killing 鈥?stop 鈥?due to from process:30657/32642/8273/10204/11070`銆乣exited due to signal 9`锛夛紝瀹炴祴鏃犳硶鍦ㄧ害 7 鍒嗛挓涓€娆＄殑澶栭儴 kill 闂撮殧鍐呭瓨娲伙紙涓€娆?run 闇€ ~9 鍒嗛挓锛夈€?- 64脳64脳9脳1锛堝彲瀹屾垚锛屼綔涓?tiling 鏁堟灉鐨勫鐓у嚑浣曪級锛?  - baseline `-e vaeTiling false`锛歚get_learned_condition completed, taking 147.29s`锛沗sampling completed, taking 33.57s`锛?*`decode_first_stage completed, taking 44.18s`**锛沗generate_video completed in 225.04s`锛沵edia 7,644 B sha256 5ecf833e544a3dbad0b462d634a1074fbfadbdb640605dbff0bb563982e07c3b銆?  - tiled `-e vaeTiling true -e tileSizeX 32 -e tileSizeY 32 -e tileOverlap 0.5`锛坱ile 涓哄垎杈ㄧ巼鐨勪竴鍗婏級锛歚get_learned_condition completed, taking 191.37s`锛沗sampling completed, taking 38.66s`锛?*`decode_first_stage completed, taking 50.20s`**锛沗generate_video completed in 280.22s`锛沵edia 7,644 B sha256 b4249ce0314a7d539d81d2953f330e6e69b6ac10ac77f2b109b3f47d149bd250銆?  - **decode 瀵规瘮锛?4.18 s 鈫?50.20 s锛?6.02 s锛?13.6%锛?*锛沢et_learned/sampling 鐨勫樊寮傚睘鍔犺浇涓庢枃鏈紪鐮佹尝鍔紝涓嶆槸 tiling 鐩存帴鏀剁泭銆?  - 鐢昏川锛氫袱娈?MP4 鐨勫抚 0/4/8 **閫愬儚绱犲畬鍏ㄤ竴鑷?*锛坢eanAbsDiff 0.000锛孭SNR = inf锛夛紝瀹瑰櫒 sha 涓嶅悓浠呭洜鍏冩暟鎹紱**鏈鎺ョ紳鎴栧櫔澹?*銆傚嵆鍦?64脳64 涓?tiling 鏈敼鍙樺浘鍍忥紝鍙槸鏇存參銆?- 128脳128 瀵圭収鏈畬鎴愶細澶栭儴 kill 鎸佺画銆佷笖鐢垫睜宸插埌 40.8 掳C锛堥€艰繎 42 掳C 涓婇檺锛夛紝鎸?30 鍒嗛挓鐩掑仠姝€?- 缁撹锛氬垎鍧楀紑鍏冲凡绔埌绔墦閫氾紙鍙傛暟鈫抪ayload鈫抧ative鈫掓棩蹇椻啋start report锛夛紝64脳64 涓?**tiling 涓嶄骇鐢熸帴缂濅絾瑙ｇ爜鏇存參锛?13.6%锛?*锛?80脳832 鐨勨€滄槸鍚︽秷闄よВ鐮佸仠婊炩€濆洜澶栭儴 lane 寮哄埗鍋滆繘绋嬭€?*鏈兘瀹炴祴**銆傚湪鎷垮埌骞插噣鐨?480脳832 瀵圭収鍓嶏紝tiling 搴斾繚鎸侀粯璁ゅ叧闂€?
## 2026-10-29 v1-14锛?8a 480脳832 鍒嗗潡瀵圭収鍐嶈瘯锛堜粛鏈祴鍑猴級+ 256虏 璇箟琛ユ祴

鍦?device-b锛圫M8750P / Android 17锛変笂閲嶈瘯 480脳832 鐨?tiling 瀵圭収銆傜鐩樹笂鐨?base 宸叉槸鍏跺畠 lane 閲嶅缓鐨?34C81E3D鈥︼紙585,284,778 B锛?4:05:50锛涜繍琛屾椂鏃ュ織纭鍚?5D 鍫嗗彔涓?vaeTiling seam锛夛紱test 涓?7EDA8A13鈥︼紙杞彂 tiling 鍙傛暟锛夈€?0:07:50 瀹夎瀹屾垚銆?
- **480脳832脳9脳1 baseline锛坴aeTiling false锛夛細INTERRUPTED**銆俙vae_tiling=off`锛宍get_learned_condition completed, taking 89.76s`锛宍sampling completed, taking 619.93s`锛堝叾涓寘鍚竴娆¤鍏跺畠鍓嶅彴搴旂敤鎸ゅ埌鍚庡彴瀵艰嚧鐨?Greeze 鍐荤粨锛夛紝decode 鏈嚭鐜?`decode_first_stage completed`锛屽湪 22 鍒嗛挓涓婇檺澶勫仠姝紱鏃犲獟浣撱€佹棤 decode 绉掓暟銆?- **480脳832脳9脳1 tiled锛?12脳288@0.5锛夛細琚?LMKD 鏉€姝?*銆俙vae_tiling=512x288@0.500000`锛宍get_learned_condition completed, taking 93.28s`锛宍sampling completed, taking 290.89s`锛沝ecode 浠?10:33:21 璧疯繍琛岀害 7 鍒?44 绉掓湭瀹屾垚锛岄殢鍚?`lowmemorykiller: thrashing`銆乣unexpected kill: 鈥?reason=lmkd:259`銆乣Process 鈥?exited due to signal 9`锛涙棤濯掍綋銆?*鍗?480脳832 闄?20 鍒嗛挓鐪嬮棬鐙楀杩樻湁绗簩涓‖闃诲锛歀MKD 鍐呭瓨鍥炴敹锛圧SS 绾?5 GB锛夈€?* 480脳832 鐨?baseline/tiled decode 瀵圭収浠嶆湭鑳芥祴鍑猴紝tiling 涔熶笉鑳介伩鍏?LMKD 鍑绘潃銆?- **256脳256脳9脳4锛宲rompt `red ball on blue table`锛氬畬鎴?*銆俙get_learned_condition` 123.20s锛沗sampling completed` 301.58s锛?*`decode_first_stage completed, taking 602.34s`**锛沗generate_video completed in 1027.13s`锛涙帰閽堟€绘椂闀?**1056s锛?7.6 鍒嗛挓锛?*锛宲eak PSS 6,748,616 KB锛沵edia 58,935 B sha256 7883540bc2eff41086af7eab990aaa96b5b7fc3683129085043d60762f4488bf锛泃okenizer status=0銆? tokens銆?*瑙嗚鍒ゅ畾锛氬僵鑹插櫔澹?鎶借薄鑹插潡锛岀湅涓嶅埌绾㈢悆鎴栬摑妗?*锛?56虏脳4 姝ヤ粛鏈嚭鐜板彲杈ㄨ瘑涓讳綋锛?7.6 鍒嗛挓涔熻繙瓒呬骇鍝侀绠楋紙decode 鍗曢」 10.0 鍒嗛挓锛夈€?- 缁撹锛?80脳832 鐨?tiling 瀵圭収闇€瑕佸悓鏃惰В鍐冲唴瀛橈紙LMKD锛変笌鏃堕暱锛涘綋鍓嶈瘉鎹笉鏀寔鈥滃垎鍧楀彲鏁?480脳832鈥濄€倀iling 缁х画榛樿鍏抽棴銆?
## 2026-10-29 v1-15锛堟柟娉曡淇锛夛細姝ゅ墠鐨勨€滆涔?NOT_MET鈥濆ぇ澶氭槸鍦?4 姝ヤ笅鍒ゅ畾鐨勶紝鑰岀敓浜ч粯璁ゆ槸 20 姝?
鏍稿鏈細璇濆叏閮ㄨ涔夊垽瀹氭墍浣跨敤姝ユ暟锛?4脳64脳9脳4锛堟帰閽?A/B锛夈€?28脳128脳9脳4銆?56脳256脳9脳4 鈥斺€?**鍧囦负 4 姝?*锛涜€屼骇鍝侀粯璁よ鏍兼槸 480脳832脳33 / 16fps / **20 姝?*锛圴ideoWorkflowTemplates.kt锛夈€?Wan 2.2 5B base 妯″瀷骞堕潪 4-step 钂搁妯″瀷锛?*4 姝ヨ繙浣庝簬鍏跺彲鐢ㄦ鏁?*锛屽洜姝?4 姝ヤ笅鈥滄棤鍙鲸璇嗕富浣撯€濆睘浜庨鏈熺幇璞★紝涓嶈兘鐩存帴鎺ㄦ柇 20 姝ヤ篃涓嶆垚绔嬶紱鍞竴涓€娆?20 姝ユ槸 64脳64脳9脳20锛?99.6s锛夛紝鑰?64脳64 鐨勫垎杈ㄧ巼鏈韩涓嶈冻浠ユ壙杞解€滅孩鐞?妗岄潰鈥濊繖绫昏涔夈€?
鈬?**姝ｇ‘鐨勮涔夊垽瀹氬疄楠?*锛堝皻鏈仛锛夛細鍦?*鐢熶骇姝ユ暟**涓嬨€佸垎杈ㄧ巼瓒冲鐨勮鏍间笂璺戜竴娆★紝渚嬪 128脳128脳9脳20锛堟鍓?64虏脳20 鏁版嵁鐐圭害 800s锛?28虏 棰勮 3鈥?脳锛夋垨 256脳256脳9脳10锛涘熀绾垮簲鏄?20 姝ヨ€岄潪 4 姝ャ€?鈬?鑻ュ紩鍏?lightx2v 4-step 钂搁鏉冮噸锛屽垯 4 姝ュ嵆涓鸿妯″瀷鐨勬瑙勬帹鐞嗛厤缃紝灞婃椂 4 姝ュ垽瀹氭墠鏈夋晥銆?
**琛屽姩椤?*锛氣憼寰呰澶囩獥鍙ｏ紝璺?128脳128脳9脳20 浣滀负棣栦釜鈥滄鏁板悎瑙勨€濈殑璇箟鏁版嵁鐐癸紱鈶″湪姝や箣鍓嶏紝涓嶅緱鍐嶇敤 4 姝ョ粨鏋滃绉拌涔夌粨璁猴紱鈶ANDOFF 鐨勨€滅敾璐?NOT_MET鈥濆簲娉ㄦ槑鍒ゅ畾姝ユ暟銆?## 2026-10-29 v1-15锛?-step 钂搁 LoRA 浼犲弬閫氶亾宸插氨缁紙榛樿鍏抽棴锛?
鍙戠幇 generate_video 鏈氨璋冪敤 sd_ctx->sd->apply_loras(sd_vid_gen_params->loras, lora_count)锛屽嵆瑙嗛璺緞鍘熺敓鏀寔 LoRA锛泈rapper 涔嬪墠浠庝笉浼犮€傜幇宸叉柊澧炲彲閫?payload 閿細

- loraPath锛坰tring锛岄粯璁ょ┖锛夈€乴oraWeight锛坒loat锛岄粯璁?0锛夛紱
- 浠呭湪 **path 闈炵┖涓?weight>0** 鏃舵瀯閫?sd_lora_t{is_high_noise=false, multiplier=loraWeight, path} 骞惰缃?params.loras/lora_count锛?- 璇佹嵁鏃ュ織鏂板 lora=off 鎴?lora=<path>@<weight>銆?
渚濊禆缁撴瀯浣擄細	ypedef struct { bool is_high_noise; float multiplier; const char* path; } sd_lora_t;锛坕nclude/stable-diffusion.h锛夈€?缂栬瘧锛?core:sd-native:assembleDebug **BUILD SUCCESSFUL**锛?8s锛?uild/evidence/v1-15/lora-seam-build2.log锛涗腑閫旈亣鍒颁竴娆￠噸澶嶅０鏄庝笌涓€娆℃棩蹇楃洰褰曠己澶憋紝鍧囧凡淇級銆?
**寰呭姙**锛氣憼闇€鐢ㄦ埛鎵瑰噯涓嬭浇 lightx2v 4-step 钂搁 LoRA锛堝眾鏃跺厛鍙栨枃浠舵竻鍗?璁稿彲/LFS sha 鏇存柊 MODEL_ASSET_SOURCES.md锛夛紱鈶¤澶囩獥鍙ｅ仛瀵圭収锛?28脳128脳9脳4 + LoRA@1.0 vs 128脳128脳9脳20 鏃?LoRA锛屽垽瀹氳涔夋槸鍚﹀嚭鐜板彲杈ㄨ瘑涓讳綋銆?## 2026-10-29 v1-16锛?-step 钂搁璧勪骇渚﹀療鍙楅樆锛堢敤鎴峰凡鎵瑰噯涓嬭浇锛?
- 鐢ㄦ埛鎵瑰噯寮曞叆 4-step 钂搁璧勪骇锛泈rapper 鐨?loraPath/loraWeight 閫氶亾宸插氨缁紙sd_video_generate.cpp sha 1585E122鈥︼紝鏃ュ織 lora=<path>@<w> 鍙瘉浼€滄槸鍚︾湡鐨勫簲鐢ㄢ€濓級銆?- **宸叉帓闄?*锛歭ightx2v/Wan2.2-Lightning 鍙湁 14B 鐨?T2V/I2V LoRA锛圵an2.2-T2V-A14B-4steps-*銆乄an2.2-I2V-A14B-4steps-lora-*锛夛紝**娌℃湁 5B TI2V 鐗堟湰**鈥斺€斿弬鑰冮」鐩敤鐨勬槸 14B 鍒嗘敮锛屼笉鑳界洿鎺ョ敤浜庢垜浠?pin 鐨?5B銆?- **寰呮煡鍊欓€?*锛欿ijai/WanVideo_comfy锛堟槸鍚﹀惈 5B Lightning LoRA锛夈€乹uanhaol/Wan2.2-TI2V-5B-Turbo锛堣捀棣忔暣妯″瀷 >3GB锛夈€乮nference-sh/Wan2.2-TI2V-5B-LightX2V-Diffusers銆?- **闃诲**锛歨uggingface.co 杩斿洖 429 闄愭祦銆乭f-mirror 鐨?API 杩斿洖鎸戞垬椤碉紱璁惧浠ｇ悊鍦ㄩ暱浠诲姟涓€斿啀娆″け璐ャ€傛湰杞湭鍙栧緱鍊欓€夋枃浠舵竻鍗?sha銆?- **鍒ゆ嵁涓嶅彉**锛歀oRA 寮犻噺鍚嶉』涓?Wan DiT 椋庢牸锛?locks.N.cross_attn.*/self_attn.*锛夋墠鑳借 sd.cpp pply_loras 搴旂敤锛涘甫 diffusion_model. 鍓嶇紑闇€鏄犲皠锛屽惁鍒欌€滄寕涓婃棤鏁堚€濄€?- **涓嶄緷璧栨柊璧勪骇鐨勬浛浠ｅ垽瀹?*锛堝凡鍙墽琛岋級锛?28脳128脳9脳20 鏃?LoRA 鈥斺€?杩欐槸鎴戜滑 base 妯″瀷鐨?*鍚堣姝ユ暟**閰嶇疆锛屽彲鍏堝洖绛斺€滆涔夊湪姝ｇ‘姝ユ暟涓嬫槸鍚︽垚绔嬧€濓紝鍐嶅喅瀹氭槸鍚﹂渶瑕佽捀棣忚祫浜с€?## 2026-10-29 v1-16锛?0 姝ワ紙鍚堣姝ユ暟锛夎涔夊垽瀹氬畬鎴?鈥斺€?浠嶆娊璞★紝涓旈绠楀鎺ㄥ樊涓や釜鏁伴噺绾?
**杩愯**锛歠9d锛圫M8550/Android16锛夛紝128脳128脳9脳20锛宲rompt all锛屼粎璺戞帰閽堢被锛?e class com.muyuchat.mca.video.VideoNativeBudgetDeviceTest锛涙鍓嶆湭鍔?-e class 浼氳窇鏁翠釜 APK 鐨?76 涓祴璇曪級銆?
| 闃舵 | 鑰楁椂 |
|---|---|
| get_learned_condition | 132.16 s |
| **sampling锛?0 姝ワ級** | **842.89 s = 14.05 min锛堚増42 s/姝ワ級** |
| decode_first_stage | 184.76 s |
| generate_video 鎬昏 | **1159.81 s = 19.3 min** |

浜х墿锛?uild/evidence/v1-16/锛坥utput.mp4 12,044B銆乸robe-frame-0.png 26,883B sha 1794fbe9鈥︼級銆?*鎶藉抚鐩锛氱传/娲嬬孩/鐧?绾㈡笎鍙樿壊甯︼紝鏃犲彲杈ㄨ瘑鐞冧綋鎴栨闈€?*

**缁撹**锛?1. 20 姝ワ紙base 姝ｈ閰嶇疆锛夊湪 128脳128 浠嶆棤涓讳綋 鈬?姝ユ暟涓嶆槸鍞竴闄愬埗锛?*鍒嗚鲸鐜?*锛圵an 2.2 宸ヤ綔鍖洪棿 480p+锛夋洿鍙兘鏄富鍥狅紱
2. **CPU 璺緞鏃犳硶浜や粯浜у搧**锛?80脳832脳33脳20 鐨勯噰鏍烽噺绾︿负 128虏脳9脳20 鐨?36脳锛堝儚绱?9.9脳 脳 甯?3.67脳锛夆噿 閲囨牱绾?8.5 灏忔椂銆佽В鐮佹洿澶氾紱涓庡弬鑰冮」鐩?NPU+4-step 钂搁鐨?832脳480/5s<3min 宸害涓や釜鏁伴噺绾э紱
3. 浜у搧璺緞蹇呴』 = **NPU(QNN) + 4-step 钂搁 + tile/娴佸紡瑙ｇ爜**锛孋PU 鍙兘浣滃疄楠岃兘鍔涖€?
**鎿嶄綔鏁欒锛堝凡澶嶇幇涓ゆ锛屽姟蹇呭浐鍖栵級**锛?m instrument 蹇呴』甯?-e class锛沺rompt 鐢ㄥ崟 token 鎴?cmd /c 鏁翠覆閲嶅畾鍚戯紱鍙栬瘉鎸夎澶?mtime 鎵?run 鐩綍锛堟湰鏈烘椂閽熸浘璺冲埌 2026-11-01锛屾寜鍚嶅瓧鎺掑簭浼氭壘閿欙級銆?## 2026-10-29 v1-17锛歍urbo 4-step LoRA 鎺ュ叆鎴愬姛锛堢湡鏈哄洖鏄捐瘉鎹級涓庤涔夎竟鐣?
**璧勪骇锛堝凡 pin锛?*锛歨um-ma/Wan2.2-TI2V-5B-Turbo-GGUF @ 9873ba17鈥?鐨?Wan22_TI2V_5B_Turbo_lora_rank_64_fp16.safetensors锛?32,348,584B锛孡FS sha256  ace5244鈥?78967b8锛堜富鏈轰笌璁惧 sha256sum 鍙屾牎楠屼竴鑷达級锛宎pache-2.0銆?
**鎺ュ叆璇佹嵁**锛歯ative 璇锋眰琛屽嚭鐜?lora=/data/local/tmp/wan_lora/Wan22_TI2V_5B_Turbo_lora_rank_64_fp16.safetensors@1.000000锛?鍚屾棩蹇楄繕鏈?stacked 5D shape (2,2,1,48,3072) -> (2,2,1,147456) 鈬?璇?APK 鍚屾椂鍚?5D 鍫嗗彔淇涓?LoRA 閫氶亾銆?
**瀵圭収锛坒9d锛?28脳128脳9锛宻eed 42锛宲rompt ball锛?*

| | 4 姝?+ LoRA | 20 姝?鏃?LoRA |
|---|---|---|
| 鏉′欢 | 145.67 s | 132.16 s |
| **閲囨牱** | **236.39 s锛?9 s/姝ワ級** | 842.89 s锛?2 s/姝ワ級 |
| 瑙ｇ爜 | 198.71 s | 184.76 s |
| **鎬昏** | **625.92 s锛?0.4 min锛?* | 1159.81 s锛?9.3 min锛?|

鈬?4 姝ユ暣浣撳揩 **1.85脳**锛堥噰鏍?3.6脳锛屼絾鍗曟鍥?LoRA 鍙樻參銆佽В鐮佷笉鍙橈級銆?
**璇箟鍒ゅ畾**锛? 姝?+ LoRA 甯э紙uild/evidence/v1-17/鈥?LORA-frame0.png锛変负绾?缁?鐧芥娊璞¤壊鍧楋紝涓?20 姝ョ殑绱?娲嬬孩鑹插甫**鏄庢樉涓嶅悓**锛圠oRA 纭疄鏀瑰彉杈撳嚭鍒嗗竷锛夛紝浣?*浠嶆棤鍙鲸璇嗙悆浣?妗岄潰**銆?缁撹锛?*128脳128 浣庝簬 Wan 2.2 鐨?480p 宸ヤ綔鍖洪棿**锛岃涔夊垽瀹氬繀椤诲湪 480p 绾ц繘琛岋紱CPU 涓?480p脳4 姝ョ害闇€ 40 鍒嗛挓浠ヤ笂 鈬?浜у搧棰勭畻鍙兘闈?NPU銆?
**娴佺▼鏁欒锛堝凡涓ゆ韪╀腑锛?*锛氬疄楠屽墠蹇呴』鏍稿璇锋眰鏃ュ織涓槸鍚﹀嚭鐜版湰娆℃柊澧炲瓧娈碉紙濡?lora=锛夛紱鍚﹀垯鍙兘鍦ㄥ埆浜烘瀯寤虹殑鏃?.so 涓婅窇锛屾妸"鏈敓鏁?璇垽涓?鏃犳晥"銆?## 2026-10-29 v1-18锛歂PU 杞崲閾剧幆澧冮獙璇侀€氳繃锛圵SL + QAIRT Linux 宸ュ叿锛?
姝ゅ墠 Windows 涓?QAIRT 鍥涗釜 python 宸ュ叿鍏ㄩ儴 exit=1锛屾牴鍥犳槸 libPyIrGraph.pyd 渚濊禆 python310.dll 鑰屼富鏈哄彧鏈?3.12銆傛湰杞崲璺緞楠岃瘉鎴愬姛锛?
| 妫€鏌?| 缁撴灉 |
|---|---|
| QAIRT SDK | E:\tools\qairt-sdk\2.45.0.260326锛屽惈 in/x86_64-linux-clang Linux 宸ュ叿閾?|
| 涓绘満 Python | 3.12锛堜笉鍏煎锛?|
| **WSL Ubuntu-22.04** | **Python 3.10.12**锛堟鏄墍闇€ ABI锛?|
| SDK Python 鍖?| lib/python 涓?qairt/ qti/ snpe/ common_utils |
| **瀹為檯鎵ц** | PYTHONPATH=<sdk>/lib/python python3 <sdk>/bin/x86_64-linux-clang/qairt-converter --help 鈫?**瀹屾暣鍙傛暟琛ㄨ緭鍑?* |

鈬?**NPU 閫氶亾鐨?鐜涓嶅彲鐢?鍓嶇疆闃诲瑙ｉ櫎**锛屾棤闇€瀹夎 Windows CPython 3.10锛岀洿鎺ョ敤 WSL 3.10銆?
**浠嶆湭鍏峰**锛歐an DiT 鐨?ONNX/鍘熷 safetensors锛堢幇鍙湁 GGUF锛夈€丵NN context 鐢熸垚銆丠TP 鎺ㄧ悊鎺ョ嚎銆備笅涓€姝ワ紙闇€鐢ㄦ埛鎵瑰噯涓嬭浇绾?10GB锛夛細
1. 鍙?Wan-AI/Wan2.2-TI2V-5B 鐨?diffusion_pytorch_model-0000x-of-00003.safetensors 鈫?杞?ONNX锛?2. qairt-converter 鈫?qnn-context-binary-generator锛圚TP V73/V79锛夛紱
3. qairt-accuracy-debugger 鏍￠獙锛?. 鎺ュ埌 :local_video锛堟浛鎹㈡墽琛屽悗绔紝璋冪敤鐐逛笉鍙橈級銆?## 2026-10-29 v1-19锛?80脳832脳9脳4 + Turbo LoRA 鍦?device-b 涓?**NOT_RUN锛堝崱鍦?native 涔嬪墠锛?*

- 閰嶇疆锛?8a锛圫M8750P/Android17锛?0.7掳C 璧锋锛夛紝480脳832銆? 甯с€? 姝ャ€乻eed 42銆乸rompt all銆乴oraPath=/data/local/tmp/wan_lora/鈥1.0锛涗笁浠跺涓?LoRA 鍧囧凡鍦ㄨ鏈烘牎楠屻€?- 璇佹嵁锛歱m list instrumentation 涓?dumpsys activity 纭 com.muyuchat.mca.devicetest.test/androidx.test.runner.AndroidJUnitRunner **Active锛? procs锛?*锛岃繘绋?pid 22197 瀛樻椿 ~35 鍒嗛挓锛涗絾 **logcat -s McaVideoNative 濮嬬粓鏃犱换浣曡緭鍑?*锛堟棤 equest geometry锛夛紝鍏ㄩ噺 logcat 涔熸棤 TestRunner/McaVideo 琛岋紝LMKD/Greeze 鏃犳湰搴旂敤璁板綍銆?- 鍒ゅ畾锛氳繍琛屽仠鍦?native 涔嬪墠鐨勯樁娈碉紙10.48GB 涓変欢濂?SHA-256 棰勬 + mmap 鍔犺浇锛夛紝device-b 涓婅闃舵鏄捐憲鎱簬 device-a锛?*娌℃湁鍙栧緱 480脳832 鐨勯噰鏍?瑙ｇ爜鏁版嵁锛屼篃鏃犲獟浣撲骇鐗?*銆傛寜浜嬪墠绾﹀畾璁颁负 **NOT_RUN**锛屼笉瑙ｉ噴涓虹敾璐ㄦ垨棰勭畻缁撹銆?- 澶勭疆锛氬凡 orce-stop 骞跺鍘?screen_off_timeout=60000銆乻tayon=false锛岃澶囨俯搴︽甯搞€?- 鏁欒锛?8a 鐨勯妫€闃舵鑰楁椂涓嶇ǔ锛屽悗缁?480p 灏濊瘯搴斿厛鍦?device-a锛堥妫€蹇€佷絾娓╁害楂橈級鎴栫敤鏇寸煭棰勭儹楠岃瘉 native 鍙繘鍏ワ紝鍐嶅喅瀹氭槸鍚﹂暱璺戙€?## 2026-10-29 v1-20 闃舵 0/1锛氬熀绾块攣瀹?+ 鍚庣璺嚎渚﹀療 + v79 闅旂 smoke锛堢敤鎴锋墽琛岋級

**闃舵 0锛堝熀绾匡紝宸茶惤鐩?uild/evidence/v1-20/phase0-baseline.txt锛?*锛欻EAD 68b6eef1鈥︼紙codex/enterprise-refactor锛夛紱宸ヤ綔鍖?739 椤瑰彉鏇?鏈窡韪紙staged 0 / untracked 342 / deleted 3锛夛紱瀛愭ā鍧?stable-diffusion.cpp=16b3846a鈥︼紙21 澶勫唴閮ㄦ敼鍔紝鍚垜浠敼鐨?gguf.cpp/ocab.cpp/	5_unigram_tokenizer.cpp锛夈€乴lama.cpp=4ceb1719鈥︼紙20 澶勶級銆?okenizers-cpp=cbdc5a2鈥︼紱CMake 3.22.1(NDK)/3.31.6銆丯DK 29.0.13113456/29.0.14206865銆丣DK 17.0.10銆丵AIRT 2.45.0.260326銆乄SL Python 3.10.12銆?
**闃舵 1锛堣矾绾夸睛瀵燂級**
- 璺嚎 A锛堝綋鍓嶅瓙妯″潡 -DGGML_HEXAGON=ON锛夛細閫夐」瀛樺湪锛坓gml/CMakeLists.txt:268锛岄粯璁?OFF锛夛紝鍚庣婧愮爜榻愬叏锛坓gml/src/ggml-hexagon/{ggml-hexagon.cpp,htp-drv.cpp,htp/,libggml-htp.inf}锛夛紝浣?CMakeLists.txt:1-22 瑕佹眰 **HEXAGON_SDK_ROOT**锛涙湰鏈烘湭鎵惧埌 Hexagon SDK锛圦AIRT 鍙甫 HTP runtime 涓?hexagon-v73/v79 搴擄紝鏃?hexagon-clang锛夈€?- 璺嚎 C锛坔appyyzy 63954d锛夛細compare leejet/master...sdcpp_external:work/qualcomm-hexagon-optimizations = **ahead 13 / behind 168 / 29 鏂囦欢**锛沇an 鐩稿叧浠?wan_vae.hpp(+139/鈭?7)銆乧onditioner.hpp(+72/鈭?)銆乬gml_block.hpp(+142/鈭?0)锛屽叾浣欎负鍥惧儚妯″瀷锛?*Hexagon 鍚庣涓嶅湪璇?delta 鍐?*锛屾潵鑷叾 ggml 瀛愭ā鍧?sdcpp_external/ggml @ f8758628锛堝悓鏍疯姹?Hexagon SDK锛夈€傞泦鎴愰渶鍚堝苟 168 涓婃父鎻愪氦 + 鎹?ggml 瀛愭ā鍧楋紙涓庢垜浠殑 5D 鍫嗗彔淇銆乴lama.cpp 鍏变韩 ggml 鍐茬獊锛夈€?- 璺嚎 B锛坰afetensors鈫扱NN context锛夛細涓庡弬鑰冨疄鐜板悓鏋勶紱QAIRT+WSL 鍙敤锛岀己 20GB 鏉冮噸涓?torch/ONNX銆?
**Nightmare 蹇収 5a6ecf7 鏋舵瀯浜嬪疄**锛氬叾 1006 鏉℃枃浠舵爲涓?*娌℃湁** 	hird_party/銆乬gml/銆乻table-diffusion.cpp锛汣++ 浠?
mqnn.cpp 绛夛紱鎵ц鏍堜负 Kotlin+JNI 鐨?QNN锛?pu/{NativeQnn,QnnRunner,TensorStore,LatentOps,Scheduler,鈥.kt锛夈€傗噿 "寮曞叆 sdcpp_external sd.cpp"涓?Nightmare 鏋舵瀯涓嶅搴旓紱Nightmare 瀵瑰簲鐨勬槸璺嚎 B 鐨勪骇鐗╁舰鎬併€?
**v79 闅旂 smoke锛堢敤鎴锋墽琛岋紝璇佹嵁 uild/evidence/v1-20/phase1-v79-smoke.md锛?*锛氱敤鏃у寘 sdcpp_external-android-htp-v79-20260412.zip锛圸IP sha256 7E8313A9鈥?59337A锛屽寘鍐?sd-cli 鐗堟湰 eqfix1-klein-wf8-weight-green-20260410+銆乧ommit 84edd21锛夊湪 **device-b锛圫M8750P / HTP v79锛?* 鐢ㄥ嚑 KB 鍚堟垚寮犻噺璺?**attention microbenchmark**锛氭棩蹇楃‘璁ら€変腑 Hexagon backend銆佸缓绔?HTP0/v79 session銆丠TP ops backend initialized successfully锛涜緭鍑?128脳2 F32锛?56 鍊煎叏鏈夐檺锛夛紝sha256 758F5B6E鈥?EF5FD34锛汵PU 34.7鈥?5.1 掳C銆乻kin 32.5 掳C銆乼hermal 0锛涜澶囦复鏃剁洰褰曞凡娓呯悊銆佹湭鏀?MCA 婧愮爜銆?鈬?**浠呰瘉鏄?v79 杩愯鐜鍙敤**锛涗笉璇佹槑 Wan 鍦?HTP 鍙窇銆佷笉璇佹槑褰撳墠婧愮爜鍙瀯寤恒€佷笉璇佹槑 MCA 宸叉帴鍏ャ€傛棫鍖?commit 涓庡綋鍓嶅垎鏀垎鍙夛紝**涓嶅緱骞跺叆 MCA**銆?
**鍐崇瓥**锛氭殏缂?20GB 鏉冮噸涓嬭浇锛堢敤鎴峰垽鏂級锛汚/C 鐨勪笅涓€閬撻棬妲涙槸鍙栧緱 Hexagon SDK 骞堕獙璇佸綋鍓嶆簮鐮佹瀯寤轰笌 Wan 绠楀瓙瑕嗙洊锛汢 闇€鏉冮噸涓?ONNX 宸ュ叿閾俱€?## 2026-10-30 v1-23 sdcpp_external/Hexagon 璺嚎锛欸1 閫氳繃銆?80p 鍗曞抚涓讳綋杈炬垚銆佸甯цВ鐮佸彈闄?
**pinned**锛欻appyYzy sdcpp 63954dc鈥?+ ggml 87586282700鈥︼紙0.24.0锛夛紱Hexagon SDK 6.7.0.1锛圱ools 21.0.04锛夈€丯DK r29銆丆Make 4.4.4銆備骇鐗╋細sd-cli 11,707,488 B銆乴ibstable-diffusion.so 162,644,640 B銆乴ibggml-htp-v73.so 853,960 B銆乴ibggml-htp-v79.so 930,472 B銆傝祫浜у湪 device-b 涓?SHA 澶嶉獙锛欴iT cf40dd6鈥︺€乂AE e40321bd鈥︺€乁MT5 2521d4de鈥︺€?
**鍏抽敭淇涓庡彂鐜?*
- sdcpp_external 寮曟搸**涓嶅惈**鎴戜滑鐨?5D GGUF 鍫嗗彔淇 鈫?鏃犳硶鍔犺浇 Wan 妯″瀷锛涚Щ妞嶅悗 stacked 5D shape into 4D (2,2,1,147456)銆乂ersion: Wan 2.2 TI2V銆乸atch_embedding_channels 147456銆?- UMT5 璇嶈〃**鏃犻渶**绉绘锛堣鏍戞棤 Android 绌鸿繑鍥炲垎鏀級銆?- --backend 蹇呴』鏄惧紡鎸囧畾锛屽惁鍒?ackend_fit 闈欓粯鍥為€€ CPU銆?- device-a/v73 鐨?60 MB astrpc_mmap 澶辫触鐢?--offload-to-cpu --max-vram 2锛?2 娈碉級缁曡繃銆?- device-b锛?1.6 GB锛夊湪甯搁┗鏉冮噸涓?OOM 閲嶅惎锛?-params-backend disk 瑙ｅ喅銆?- VAE on HTP锛歷73 瑙﹀彂 dspqueue_read 纭敊璇紙ggml-hexagon.cpp:3281锛夛紱**v79 鍗曞抚鍙敤**銆?
**瀹炴祴锛?8a/v79锛? 姝ワ紝seed 42锛?*
| 閰嶇疆 | 閲囨牱 | 瑙ｇ爜 | 鎬?| 缁撴灉 |
|---|---|---|---|---|
| 832脳480脳1锛寁ae=HTP0 | 128.58 s | **8.26 s** | ~190 s | **绾㈢悆+钃濆簳锛屼富浣撲笌鎻愮ず璇嶄竴鑷?* |
| 832脳480脳1锛寁ae=CPU | 128.65 s | 308.61 s | 575.13 s | 鍚屼笂锛圕PU 瑙ｇ爜鎱?37脳锛?|
| 256虏脳1 / 脳5 / 脳9锛圕PU 瑙ｇ爜锛?| 40.0/53.4/68.6 s | 50.9/214.6/382.8 s | 鈥?| 1 甯ф湁鏁堛€? 甯ф湁鏁堛€?*9 甯х函鐧?* |
| 128虏脳9 | 36.85 s | 93.17 s | ~233 s | 鏈夋晥 |
| 832脳480脳5 涓?脳9锛坴ae=HTP0锛?| 鈥?| **澶辫触** | 鈥?| wan_vae ... failed during weight preparation锛堣嚜鍔?tiling 閲嶈瘯浜﹀け璐ワ級 |

**瑙ｇ爜鍖呯粶**锛欳PU VAE 涓嬫湁鏁堜笂闄愮害 **0.4M 鍍忕礌**锛圵脳H脳甯э級锛?-vae-tiling/--temporal-tiling 浠呴檷缂撳啿 6%锛屼笉鑳界獊鐮淬€?
**闃舵鍒ゅ畾**锛欸1 DiT on HTP **PASS**锛坴73+v79锛宲lacement 璇佹嵁榻愬叏銆佽法鏋舵瀯涓€鑷达級锛汫1 VAE on HTP **PARTIAL**锛坴79 鍗曞抚 8.26 s锛屽甯?OOM锛夛紱G2 鍔犺浇 **PASS**锛?*G3 涓讳綋璐ㄩ噺 PASS锛?80p 鍗曞抚锛?*锛汫3 瑙嗛 / G4 480p **NOT MET / BLOCKED**锛圴AE 瀹归噺锛夛紱G5/G6 NOT_RUN銆?
**鍓╀綑宸ユ椂**锛歏AE 澶氬抚瑙ｇ爜鏀归€狅紙閫愬抚/鍒嗗潡/TAEHV锛夌害 3鈥? 浜烘棩锛涗箣鍚?G4 涓?G6 鎺ョ嚎绾?1鈥? 鍛ㄣ€傝瘉鎹洰褰?uild/evidence/v1-23/锛堝惈 PHASE_SUMMARY.md銆乨ecode-envelope.md銆佸悇 run 鏃ュ織涓?PNG/AVI锛夈€
## 2026-10-30 端到端验收：/v1/video + 外部 sdcpp 后端 → 200 且产物可验证

**链路**：POST /v1/video（X-API-Key）→ 鉴权/合同 → VideoGenerationOwner → :local_video worker → native（engine=sdcpp_external）→ 帧 spool → MediaCodec → 媒体发布事务。

**实测（Lead 独立复现，非子智能体数据）**
- HTTP **200**，state=COMPLETED、stage=FINALIZE
- data[0]：ideo/mp4、h264、256×256、**frame_count=5**、**fps=24**、duration_us=208300、size_bytes=10861、sha256=b445f04f…a6098d8
- 事件链：QUEUED→PREPARING→CONDITIONING→SAMPLING→DECODING→ENCODING→PUBLISHING→COMPLETED
- **产物校验**：用 db exec-out run-as … cat 取出 MP4，文件 **10,861 B / sha256 与 API 完全一致**；fprobe：h264 / 256×256 / r_frame_rate=24/1 / nb_frames=5 / duration=0.208333
- 两次独立运行：尺寸/时长/帧数/帧率一致，**sha256 不同**（采样非确定性，不承诺像素级可复现）

**引擎选择（用户要求的两条入口均已实现）**
- UI：视频页"实验性视频后端"开关，**默认关闭**；开启才在提交时冻结 engine=sdcpp_external
- API：/v1/video 的 engine（""/sdcpp_external）与 ackend（cpu/htp，默认 htp）；非法值 400
- **默认路径逐字节等价**（单测证明：无 engine 时请求体与改造前完全一致）

**边界（未通过项，如实记录）**
- **App 进程内 HTP：BLOCKED(platform-policy)** —— astrpc open_shell failed for domain 3 →  x80000406；untrusted_app 无法使用未签名 PD。同一引擎在 shell 侧（独立 sd-cli）已验证 HTP 可用（480p 采样 128.58 s；128²/256² 流式 exit=0）
- **480p 多帧：BLOCKED(device-limit)** —— device-b(11.6 GB) 与 device-a(15.5 GB) 共 10 次尝试，均以内存耗尽掉线或结温/外壳温度越限终止
- 本次端到端为 ackend=cpu（256²×5 约 350 s），**不代表 NPU 性能**

**证据**：uild/evidence/v1-26/{E2E-VERIFIED.md, e2e-256x256x5.mp4, video-stage-chain-fix.md, INAPP-DSP-ACCESS-BLOCKER.md, video-engine-selection.md}

**待决**：产品默认 ackend 取值（htp 现状 / cpu / htp 失败自动回落 cpu）。回落方案需改 native 或 owner，属行为变更，尚未实施。
## 2026-10-31 23:49 —— App 进程内 480×832×5 视频在 NPU（HTP）上首次跑通

**结果**：POST /v1/video（engine=sdcpp_external, backend=htp）返回 **HTTP 200**，
state=COMPLETED、stage=FINALIZE、external_backend=htp（顶层与 execution 内一致，无回落）。

- 产物 media-667a03f6-…：**30,728 B**，sha256=6b243808…9f1286，与 API 返回**完全一致**；
  fprobe：h264 / 480×832 / 24 fps / 5 帧 / 208.3 ms。
- 运行时：htp canary devices=2 [HTP0,CPU]、equested=[htp/cpu/cpu] actual=[htp/cpu/cpu]、rames=5 delivered=5。
- 耗时 elapsed_ms=400870（4 步 / TAE 解码）。

**达成所需的 6 个修正（全部代码层，无需 root / 系统签名 / 高通授权）**

1. libcdsprpc.so 不再打包进 APK（它是系统 public library，打包反而破坏 vendor 依赖解析 ⇒ 之前只看到 [CPU]）；
2. chdir 到骨架部署目录，使 DSP loader 的 ./ 搜索项命中（ADSP_LIBRARY_PATH 对 App 进程无效）；
3. 预检算术 canary 改为 opt-in（它在 App 进程 graph_compute 会 SIGSEGV）；
4. 新增 Q4_0_Q4_K_M profile：编码器 6.04 GB → 3.66 GB，避免 480p 采样期被 LMK 杀；
5. 引擎日志镜像到 logcat（tag McaWanEngine），否则 App 内失败原因不可见；libmca_wan.so 为预编译产物，须 NDK 手动重编；
6. **TAEHV 不支持时序分块**：	emporal_tiling + tile_frames=1 触发 left output frame 1 uncovered，解码 0 帧 ⇒ TAE profile 下关闭分块。

**据此更正的三条旧结论**

- "App 内 NPU 受平台权限限制、无法靠代码解决" —— **错误**，根因是库打包 + 骨架搜索路径 + canary 崩溃；
- "480p 多帧受设备内存限制、App 内不可实现" —— 对**完整 Wan VAE** 成立，对 **TAE + Q4_K_M** 路径不成立；
- "多帧噪声因缺 cfg/负面提示" —— **错误**，真因是 HTP elementwise 部分广播（patch 0011）。

**边界**：本结果为 TAE 解码；完整 Wan VAE 480p×5 仍不可用；未与对标输出做画质逐帧对比；
Turbo LoRA 未接入 App 请求链；UI 默认仍是 33 帧/16 fps/20 步；有界输出与单 owner 仍 NOT_RUN。

**证据**：uild/evidence/v1-27/INAPP-HTP-480P-PASS.md、inapp-htp-480p-media-667a03f6-….mp4、
inapp-480p-f0.png、inapp-480p-f4.png。