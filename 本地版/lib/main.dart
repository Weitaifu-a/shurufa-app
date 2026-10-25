import 'dart:async';
import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:http/http.dart' as http;

// ===== 下面两个开关由生成脚本自动填写，你不用改 =====
const bool kCloudMode = false;
const String kVersionName = '本地版';
const String kAppTitle = '拼音输入面板';

void main() {
  runApp(const MyApp());
}

class MyApp extends StatelessWidget {
  const MyApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      debugShowCheckedModeBanner: false,
      title: '$kAppTitle $kVersionName',
      theme: ThemeData(
        useMaterial3: true,
        colorScheme: ColorScheme.fromSeed(seedColor: const Color(0xFF2E7DF7)),
      ),
      home: const HomePage(),
    );
  }
}

class WordItem {
  final String w; // 词
  final String p; // 拼音（无声调，纯字母）
  const WordItem(this.w, this.p);
}

class IdiomItem {
  final String w; // 成语
  final String p; // 拼音
  final String d; // 释义
  const IdiomItem(this.w, this.p, this.d);
}

class HomePage extends StatefulWidget {
  const HomePage({super.key});

  @override
  State<HomePage> createState() => _HomePageState();
}

class _HomePageState extends State<HomePage> {
  final TextEditingController _resultCtrl = TextEditingController();
  final TextEditingController _pyCtrl = TextEditingController();

  final List<WordItem> _words = [];
  final List<IdiomItem> _idioms = [];

  bool _loading = true;
  String _loadMsg = '正在准备词库…';

  List<String> _candidates = [];

  int _extraMode = 0; // 0=无 1=组词 2=成语
  String _extraTitle = '';
  final List<WordItem> _extraWords = [];
  final List<IdiomItem> _extraIdioms = [];

  String _translated = '';
  bool _translating = false;
  Timer? _timer;

  @override
  void initState() {
    super.initState();
    _loadData();
  }

  @override
  void dispose() {
    _timer?.cancel();
    _resultCtrl.dispose();
    _pyCtrl.dispose();
    super.dispose();
  }

  Future<void> _loadData() async {
    try {
      setState(() => _loadMsg = '正在读取词语库…');
      final ws = await rootBundle.loadString('assets/words.txt');
      for (final line in const LineSplitter().convert(ws)) {
        final i = line.indexOf('\t');
        if (i <= 0) continue;
        _words.add(WordItem(line.substring(0, i), line.substring(i + 1)));
      }

      setState(() => _loadMsg = '正在读取成语库…');
      final isx = await rootBundle.loadString('assets/idioms.txt');
      for (final line in const LineSplitter().convert(isx)) {
        final parts = line.split('\t');
        if (parts.length < 2) continue;
        _idioms.add(IdiomItem(
          parts[0],
          parts[1],
          parts.length > 2 ? parts[2] : '',
        ));
      }

      setState(() => _loadMsg = '正在读取离线词典…');
      final ds = await rootBundle.loadString('assets/cedict.txt');
      for (final line in const LineSplitter().convert(ds)) {
        final i = line.indexOf('\t');
        if (i <= 0) continue;
        _dictGlobal.putIfAbsent(line.substring(0, i), () => line.substring(i + 1));
      }

      if (!mounted) return;
      setState(() => _loading = false);
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _loading = false;
        _loadMsg = '词库读取失败：$e';
      });
    }
  }

  // ---------- 拼音候选 ----------
  void _onPyChanged(String v) {
    final q = v.toLowerCase().replaceAll(RegExp(r'[^a-z]'), '');
    if (q.isEmpty) {
      setState(() => _candidates = []);
      return;
    }
    final exact = <String>[];
    final pre = <String>[];
    for (final w in _words) {
      if (w.p == q) {
        if (exact.length < 400) exact.add(w.w);
      } else if (w.p.startsWith(q)) {
        if (pre.length < 400) pre.add(w.w);
      }
    }
    exact.sort((a, b) => a.length.compareTo(b.length));
    pre.sort((a, b) => a.length.compareTo(b.length));
    final out = <String>[];
    for (final s in exact) {
      if (out.length >= 40) break;
      if (!out.contains(s)) out.add(s);
    }
    for (final s in pre) {
      if (out.length >= 80) break;
      if (!out.contains(s)) out.add(s);
    }
    setState(() => _candidates = out);
  }

  void _append(String s) {
    setState(() {
      _resultCtrl.text = _resultCtrl.text + s;
      _pyCtrl.clear();
      _candidates = [];
      _extraMode = 0;
      _extraWords.clear();
      _extraIdioms.clear();
    });
    _resultCtrl.selection = TextSelection.fromPosition(
      TextPosition(offset: _resultCtrl.text.length),
    );
    _scheduleTranslate();
  }

  // ---------- 自动组词 ----------
  void _doZuCi() {
    final t = _resultCtrl.text.trim();
    if (t.isEmpty) {
      _toast('先在上方文本框里输入或上屏一个字');
      return;
    }
    final key = t.substring(t.length - 1);
    final res = <WordItem>[];
    for (final w in _words) {
      if (w.w.length > 1 && w.w.contains(key)) {
        res.add(w);
        if (res.length >= 80) break;
      }
    }
    res.sort((a, b) => a.w.length.compareTo(b.w.length));
    setState(() {
      _extraMode = 1;
      _extraTitle = '组词：含「$key」的词语（${res.length}）';
      _extraWords
        ..clear()
        ..addAll(res);
      _extraIdioms.clear();
    });
  }

  // ---------- 成语 ----------
  void _doChengYu() {
    final t = _resultCtrl.text.trim();
    if (t.isEmpty) {
      _toast('先在上方文本框里输入或上屏一个字');
      return;
    }
    final key = t.substring(t.length - 1);
    final res = <IdiomItem>[];
    for (final it in _idioms) {
      if (it.w.contains(key)) {
        res.add(it);
        if (res.length >= 80) break;
      }
    }
    setState(() {
      _extraMode = 2;
      _extraTitle = '成语：含「$key」的成语（${res.length}）';
      _extraIdioms
        ..clear()
        ..addAll(res);
      _extraWords.clear();
    });
  }

  // ---------- 自动翻译 ----------
  void _scheduleTranslate() {
    _timer?.cancel();
    _timer = Timer(const Duration(milliseconds: 900), () async {
      final t = _resultCtrl.text.trim();
      if (t.isEmpty) {
        if (mounted) setState(() => _translated = '');
        return;
      }
      if (mounted) setState(() => _translating = true);
      final r = await translateText(t);
      if (!mounted) return;
      setState(() {
        _translated = r;
        _translating = false;
      });
    });
  }

  void _toast(String msg) {
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(content: Text(msg), duration: const Duration(seconds: 2)),
    );
  }

  Future<void> _copy(String text) async {
    if (text.isEmpty) {
      _toast('没有可复制的内容');
      return;
    }
    await Clipboard.setData(ClipboardData(text: text));
    _toast('已复制，去微信/备忘录长按粘贴即可');
  }

  // ================= 界面 =================
  @override
  Widget build(BuildContext context) {
    if (_loading) {
      return Scaffold(
        appBar: AppBar(title: Text('$kAppTitle · $kVersionName')),
        body: Center(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              const CircularProgressIndicator(),
              const SizedBox(height: 16),
              Text(_loadMsg),
            ],
          ),
        ),
      );
    }

    final cs = Theme.of(context).colorScheme;
    return Scaffold(
      appBar: AppBar(
        title: Text('$kAppTitle · $kVersionName'),
        actions: [
          Padding(
            padding: const EdgeInsets.only(right: 12),
            child: Center(
              child: Text(
                kCloudMode ? '联网增强' : '纯离线',
                style: TextStyle(
                  fontSize: 13,
                  color: kCloudMode ? cs.primary : Colors.grey[700],
                ),
              ),
            ),
          ),
        ],
      ),
      body: SafeArea(
        child: Column(
          children: [
            // 结果输入区
            Padding(
              padding: const EdgeInsets.fromLTRB(12, 10, 12, 6),
              child: TextField(
                controller: _resultCtrl,
                maxLines: 3,
                minLines: 3,
                onChanged: (_) => _scheduleTranslate(),
                decoration: InputDecoration(
                  hintText: '这里是你打出来的内容（也可以直接在这里手写/粘贴）',
                  border: const OutlineInputBorder(),
                  suffixIcon: IconButton(
                    icon: const Icon(Icons.backspace_outlined),
                    onPressed: () {
                      final t = _resultCtrl.text;
                      if (t.isNotEmpty) {
                        _resultCtrl.text = t.substring(0, t.length - 1);
                      }
                      setState(() {});
                      _scheduleTranslate();
                    },
                  ),
                ),
              ),
            ),

            // 译文区
            Container(
              width: double.infinity,
              margin: const EdgeInsets.fromLTRB(12, 0, 12, 8),
              padding: const EdgeInsets.all(10),
              decoration: BoxDecoration(
                color: cs.secondaryContainer.withOpacity(0.35),
                borderRadius: BorderRadius.circular(8),
              ),
              child: Row(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  const Text('翻译：', style: TextStyle(fontWeight: FontWeight.bold)),
                  Expanded(
                    child: _translating
                        ? const Text('翻译中…')
                        : Text(
                            _translated.isEmpty ? '（输入中文后自动翻译）' : _translated,
                          ),
                  ),
                  if (_translated.isNotEmpty)
                    InkWell(
                      onTap: () => _copy(_translated),
                      child: Icon(Icons.copy, size: 18, color: cs.primary),
                    ),
                ],
              ),
            ),

            // 拼音输入行
            Padding(
              padding: const EdgeInsets.fromLTRB(12, 0, 12, 6),
              child: TextField(
                controller: _pyCtrl,
                autofocus: false,
                keyboardType: TextInputType.visiblePassword,
                onChanged: _onPyChanged,
                decoration: InputDecoration(
                  isDense: true,
                  hintText: '在这里打拼音，比如 wo、nihao、chengyu',
                  prefixIcon: const Icon(Icons.keyboard_alt_outlined),
                  border: const OutlineInputBorder(),
                  suffixIcon: _pyCtrl.text.isEmpty
                      ? null
                      : IconButton(
                          icon: const Icon(Icons.close),
                          onPressed: () {
                            _pyCtrl.clear();
                            setState(() => _candidates = []);
                          },
                        ),
                ),
              ),
            ),

            // 候选词
            SizedBox(
              height: 48,
              child: _candidates.isEmpty
                  ? const Center(
                      child: Text('候选词会显示在这里', style: TextStyle(color: Colors.grey)),
                    )
                  : ListView.builder(
                      scrollDirection: Axis.horizontal,
                      itemCount: _candidates.length,
                      itemBuilder: (_, i) {
                        final w = _candidates[i];
                        return Padding(
                          padding: const EdgeInsets.symmetric(horizontal: 4),
                          child: ActionChip(
                            label: Text(w),
                            onPressed: () => _append(w),
                          ),
                        );
                      },
                    ),
            ),

            // 功能按钮
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
              child: Row(
                children: [
                  Expanded(child: OutlinedButton.icon(onPressed: _doZuCi, icon: const Icon(Icons.auto_awesome), label: const Text('组词'))),
                  const SizedBox(width: 6),
                  Expanded(child: OutlinedButton.icon(onPressed: _doChengYu, icon: const Icon(Icons.menu_book), label: const Text('成语'))),
                  const SizedBox(width: 6),
                  Expanded(child: OutlinedButton.icon(onPressed: _scheduleTranslate, icon: const Icon(Icons.translate), label: const Text('翻译'))),
                  const SizedBox(width: 6),
                  Expanded(child: FilledButton.icon(onPressed: () => _copy(_resultCtrl.text), icon: const Icon(Icons.copy), label: const Text('复制'))),
                ],
              ),
            ),

            // 结果列表
            Expanded(
              child: _extraMode == 0
                  ? const Center(child: Text('点上面的「组词 / 成语」，结果会显示在这里'))
                  : Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Padding(
                          padding: const EdgeInsets.fromLTRB(14, 4, 12, 2),
                          child: Text(
                            _extraTitle,
                            style: const TextStyle(fontWeight: FontWeight.bold),
                          ),
                        ),
                        Expanded(
                          child: ListView.builder(
                            itemCount: _extraMode == 1 ? _extraWords.length : _extraIdioms.length,
                            itemBuilder: (_, i) {
                              if (_extraMode == 1) {
                                final w = _extraWords[i];
                                return ListTile(
                                  dense: true,
                                  title: Text(w.w),
                                  subtitle: Text(w.p),
                                  trailing: const Icon(Icons.add_circle_outline),
                                  onTap: () => _append(w.w),
                                );
                              }
                              final it = _extraIdioms[i];
                              return ListTile(
                                dense: true,
                                title: Text(it.w),
                                subtitle: Text(
                                  it.d.isEmpty ? it.p : '${it.p}　${it.d}',
                                  maxLines: 2,
                                  overflow: TextOverflow.ellipsis,
                                ),
                                trailing: const Icon(Icons.add_circle_outline),
                                onTap: () => _append(it.w),
                              );
                            },
                          ),
                        ),
                      ],
                    ),
            ),
          ],
        ),
      ),
    );
  }
}

// ============ 翻译实现 ============
Future<String> translateText(String text) async {
  final src = text.trim();
  if (src.isEmpty) return '';

  final hasChinese = RegExp(r'[\u4e00-\u9fff]').hasMatch(src);

  if (kCloudMode) {
    try {
      final online = await _translateOnline(
        src,
        hasChinese ? 'zh-CN' : 'en',
        hasChinese ? 'en' : 'zh-CN',
      );
      if (online != null && online.trim().isNotEmpty) {
        return online.trim();
      }
    } catch (_) {
      // 联网失败就用离线词典
    }
  }
  return _translateOffline(src);
}

Future<String?> _translateOnline(String q, String sl, String tl) async {
  final uri = Uri.https('translate.googleapis.com', '/translate_a/single', {
    'client': 'gtx',
    'sl': sl,
    'tl': tl,
    'dt': 't',
    'q': q,
  });
  final resp = await http
      .get(uri, headers: {'User-Agent': 'Mozilla/5.0'})
      .timeout(const Duration(seconds: 10));
  if (resp.statusCode != 200) return null;
  final data = jsonDecode(resp.body);
  final buf = StringBuffer();
  if (data is List && data.isNotEmpty && data[0] is List) {
    for (final seg in data[0]) {
      if (seg is List && seg.isNotEmpty && seg[0] != null) {
        buf.write(seg[0].toString());
      }
    }
  }
  final s = buf.toString().trim();
  return s.isEmpty ? null : s;
}

String _translateOffline(String text) {
  final src = text.trim();
  final out = StringBuffer();
  int i = 0;
  while (i < src.length) {
    String? hit;
    final maxLen = 8;
    for (int len = maxLen; len >= 1; len--) {
      if (i + len > src.length) continue;
      final w = src.substring(i, i + len);
      final v = _dictGlobal[w];
      if (v != null) {
        hit = v;
        i += len;
        break;
      }
    }
    if (hit != null) {
      out.write(hit);
    } else {
      final ch = src.substring(i, i + 1);
      final v = _dictGlobal[ch];
      out.write(v ?? ch);
      i += 1;
    }
    if (i < src.length) out.write(' ');
  }
  return out.toString();
}

// 离线词典（由应用启动时填充）
final Map<String, String> _dictGlobal = {};
