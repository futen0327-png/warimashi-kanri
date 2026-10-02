# キー診断ツール（debug ビルド専用）

Bluetooth リモコン（エレコム P-SRB01BK など）のボタンが、Android にどんなキーイベントとして届くかを調べるための画面。
将来「マイク ON/OFF のトグル」に使うための事前調査用で、**本番機能（音声認識・Firebase 送信など）には一切手を入れていない**。

- ファイルはすべて `app/src/debug/` にある。release ビルドには含まれない
- 本番の `MainActivity`・起動画面・`src/main` のマニフェストは変更なし

| ファイル | 内容 |
|---|---|
| `app/src/debug/AndroidManifest.xml` | 診断画面・サービス・権限（debug ビルドにだけマージされる） |
| `app/src/debug/java/.../diag/KeyDiagnosticActivity.kt` | フェーズ1: 画面ON・前面時のキー受信とログ表示 |
| `app/src/debug/java/.../diag/KeyDiagnosticService.kt` | フェーズ2: 画面OFF・バックグラウンド時（MediaSession） |
| `app/src/debug/java/.../diag/KeyLog.kt` | 両方が書き込む共通ログ（logcat タグ `KeyDiag` にも出力） |

## Android Studio での実行

1. Android Studio で `android/` フォルダを開き、Gradle Sync が終わるのを待つ
2. 左下の **Build Variants** で `app` が **debug** になっていることを確認（既定で debug）
3. 実機を USB（またはワイヤレスデバッグ）で接続し、上部の実行構成 `app` のまま **Run ▶**
4. いつもどおり本番の画面（割増オペ 音声）が起動する。ホーム画面に戻ると、
   debug ビルドでだけ **「キー診断」** アイコンが増えているので、それをタップ

### Run で直接診断画面を開きたいとき（任意）

`Run > Edit Configurations… > app > General > Launch Options` の **Launch** を
`Specified Activity` にし、**Activity** に `jp.warimashi.voiceop.diag.KeyDiagnosticActivity` を指定する。
元に戻すときは `Default Activity` に戻す（プロジェクトのファイルは変わらない）。

※ adb が使える場合は `adb shell am start -n jp.warimashi.voiceop/.diag.KeyDiagnosticActivity` でも開ける。

## 画面の見方

上から順に:

- **接続中の入力デバイス**（`InputDevice.getDeviceIds()`）: `id`・名前・`src`（sources）・外部/内蔵・キーボード種別・vendor/product ID、
  そのデバイスが「持つ」と申告しているキー（音量UP/DOWN・Enter・カメラ・再生/一時停止・ヘッドセット）。
  リモコンをペアリング・接続すると自動で更新され、ログにも「接続」と出る。手動の **更新** ボタンもある
- **キーをこの画面で消費する**: ON にすると、届いたキーをこの画面で握りつぶす（音量が変わらない・Enter でボタンが押されない）。
  戻るキーだけは消費しないので、ON でも戻るで閉じられる
- **画面OFF診断で無音を再生する**: 後述（フェーズ2）
- **画面OFF診断を開始 / 停止**
- **ログをクリア / ログをコピー**: コピーはデバイス一覧＋ログ全文をクリップボードへ（LINE やメールに貼り付けて共有できる）
- **ログ**: 新しい行が下に追加される

ログ1行の形式:

```
12:34:56.789 [Activity.dispatchKeyEvent] DOWN KEYCODE_VOLUME_UP(24) repeat=0 scan=115 src=0x101(KEYBOARD) dev=12 "P-SRB01BK"
 時刻(ミリ秒)  どこで受けたか               action keyCode名(値)       repeatCount scanCode source        deviceId InputDevice名
```

`[どこで受けたか]` の意味:

| 表示 | 意味 |
|---|---|
| `Activity.dispatchKeyEvent` | 診断画面が前面のとき、最初にキーを受けた場所（すべてのキーがここを通る） |
| `Activity.onKeyDown` / `onKeyUp` | その後に届いたハンドラ |
| `Service.onMediaButtonEvent` | 画面OFF・バックグラウンドで、メディアキー（再生/一時停止・ヘッドセットボタン等）として届いた |
| `Service` `VolumeProvider.onAdjustVolume direction=+1/-1` | 画面OFF・バックグラウンドで、音量キーとして届いた（端末の音量は変わらない） |
| `Service` `Callback.onPlay` など | 上のメディアキーを Android が「再生」「一時停止」などに変換したもの |
| `Service` `---- 画面OFF ----` / `画面ON` / `ロック解除` | 画面の状態の変化（キーの記録と時刻を突き合わせるため） |
| `InputDevice` | 入力デバイスの接続・切断・変更 |

## 実機での確認手順

### 0. 準備

1. リモコンを Android の Bluetooth 設定でペアリングし、「接続済み」にする
2. Android Studio から Run でインストール → ホーム画面の「キー診断」を開く
3. 「接続中の入力デバイス」にリモコンらしい名前（例: `P-SRB01BK`、`AB Shutter3` のような名前のこともある）が
   **外部**・`src` に `KEYBOARD` 付きで出ていれば HID として認識されている。出ていなければ一度リモコンの電源を入れ直す

### 1. フェーズ1: 画面ON・前面

1. 「キーをこの画面で消費する」を **OFF** のまま、リモコンのボタンを **1回短く** 押す
   - ログに DOWN と UP が出るか、`KEYCODE_...` が何か（音量UP・Enter・カメラなど）、`dev` の名前がリモコンか を見る
   - 1回押しで複数のキー（例: 音量UP と Enter の両方）が出る機種もあるので、すべて記録する
2. **長押し** もしてみる（`repeat=1,2,…` と続くか、UP がいつ来るか）
3. **連打** もしてみる（取りこぼしがないか）
4. OFF のときに端末の音量が変わるかどうかを見ておく
5. 「キーをこの画面で消費する」を **ON** にして同じことをし、音量が変わらなくなるか確認
6. **ログをコピー** して保存

### 2. フェーズ2: 画面OFF（ポケットに入れた状態）

1. 「ログをクリア」→「**画面OFF診断を開始**」
   - Android 13 以降は初回に通知の許可を聞かれるので「許可」（拒否しても診断は動くが、通知から停止できない）
   - 通知欄に「キー診断: 画面OFF診断中」が出る
2. 電源ボタンで **画面を消す**。数秒待ってからリモコンを 1回・長押し・連打 と押す
   - ロック画面が点いた状態でも試しておくとよい（画面OFFとは届き方が違うことがある）
   - 数分放置してから押す（スリープが深くなっても届くか）も試す
3. 画面を点けてロック解除 → 診断画面（または通知をタップ）でログを見る
   - `---- 画面OFF ----` と `---- 画面ON ----` の間に `Service` の行があれば、画面OFFでも届いている
   - 音量キー型のリモコンなら `VolumeProvider.onAdjustVolume`、メディアキー型なら `onMediaButtonEvent` に出るはず
4. 何も出ないときは「画面OFF診断を停止」→「**画面OFF診断で無音を再生する**」を ON →「開始」でもう一度
   （メディアキーは「最後に音を鳴らしたアプリ」に届くため、無音を再生して優先を取る）
5. 終わったら「画面OFF診断を停止」（または通知の「停止」）。**ログをコピー** して保存

### 3. 報告してほしい内容

- 「ログをコピー」で取れた全文（フェーズ1・フェーズ2 それぞれ）
- 端末の機種名と Android のバージョン（ログの先頭行に出る）
- フェーズ1で消費OFFのとき、音量が変わったかどうか
- フェーズ2で、無音再生 OFF / ON それぞれで届いたかどうか

## ログを PC で見る（任意）

Android Studio の **Logcat** で `tag:KeyDiag` と絞り込むと、画面のログと同じ行がリアルタイムに流れる。
