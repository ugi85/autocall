import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import 'auth_provider.dart';
import 'call_queue_provider.dart';
import 'queue_screen.dart';

class LoginScreen extends StatefulWidget {
  const LoginScreen({super.key});

  @override
  State<LoginScreen> createState() => _LoginScreenState();
}

class _LoginScreenState extends State<LoginScreen> {
  final _formKey = GlobalKey<FormState>();
  final _usernameController = TextEditingController();
  final _passwordController = TextEditingController();
  final _scrollController = ScrollController();
  final _passwordFocusNode = FocusNode();

  bool _obscurePassword = true;

  @override
  void initState() {
    super.initState();
    _passwordFocusNode.addListener(() {
      if (_passwordFocusNode.hasFocus) {
        Future.delayed(const Duration(milliseconds: 300), () {
          if (_scrollController.hasClients) {
            _scrollController.animateTo(
              _scrollController.position.maxScrollExtent,
              duration: const Duration(milliseconds: 250),
              curve: Curves.easeOut,
            );
          }
        });
      }
    });

    WidgetsBinding.instance.addPostFrameCallback((_) {
      final auth = context.read<AuthProvider>();
      if (_usernameController.text.isEmpty && auth.savedUsername.isNotEmpty) {
        _usernameController.text = auth.savedUsername;
      }
    });
  }

  @override
  void dispose() {
    _usernameController.dispose();
    _passwordController.dispose();
    _passwordFocusNode.dispose();
    _scrollController.dispose();
    super.dispose();
  }

  Future<void> _handleLogin() async {
    final auth = context.read<AuthProvider>();
    auth.clearError();

    if (!_formKey.currentState!.validate()) {
      return;
    }

    final success = await auth.login(
      username: _usernameController.text,
      password: _passwordController.text,
    );

    if (!mounted) return;

    if (success) {
      // Sinkronkan URL kontak & muat kontak jika antrean kosong
      final queueProvider = context.read<CallQueueProvider>();
      if (auth.loginApiUrl.isNotEmpty && queueProvider.contactsApiUrl != auth.loginApiUrl) {
        queueProvider.setContactsApiUrl(auth.loginApiUrl);
      }
      if (queueProvider.queue.isEmpty) {
        queueProvider.syncContactsSilently();
      }

      Navigator.of(context).pushReplacement(
        MaterialPageRoute(
          builder: (_) => const QueueScreen(showLoginSuccessWelcome: true),
        ),
      );
    }
  }

  @override
  Widget build(BuildContext context) {
    final auth = context.watch<AuthProvider>();
    final theme = Theme.of(context);
    final colorScheme = theme.colorScheme;

    final isKeyboardOpen = MediaQuery.of(context).viewInsets.bottom > 0;

    return Scaffold(
      resizeToAvoidBottomInset: true,
      body: SafeArea(
        child: Center(
          child: SingleChildScrollView(
            controller: _scrollController,
            physics: const BouncingScrollPhysics(parent: AlwaysScrollableScrollPhysics()),
            padding: EdgeInsets.fromLTRB(
              24.0,
              isKeyboardOpen ? 8.0 : 16.0,
              24.0,
              isKeyboardOpen ? 180.0 : 16.0,
            ),
            child: ConstrainedBox(
              constraints: const BoxConstraints(maxWidth: 440),
              child: Form(
                key: _formKey,
                child: Column(
                  mainAxisAlignment: MainAxisAlignment.center,
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    // Brand / Logo Section (compact row when keyboard is open)
                    if (isKeyboardOpen)
                      Row(
                        mainAxisAlignment: MainAxisAlignment.center,
                        children: [
                          Container(
                            width: 36,
                            height: 36,
                            decoration: BoxDecoration(
                              color: colorScheme.primaryContainer,
                              borderRadius: BorderRadius.circular(10),
                            ),
                            child: Icon(
                              Icons.phone_forwarded_rounded,
                              size: 20,
                              color: colorScheme.primary,
                            ),
                          ),
                          const SizedBox(width: 10),
                          Text(
                            'AutoCall',
                            style: theme.textTheme.titleMedium?.copyWith(
                              fontWeight: FontWeight.bold,
                              color: colorScheme.primary,
                            ),
                          ),
                        ],
                      )
                    else ...[
                      Center(
                        child: Container(
                          width: 76,
                          height: 76,
                          decoration: BoxDecoration(
                            color: colorScheme.primaryContainer,
                            borderRadius: BorderRadius.circular(20),
                            boxShadow: [
                              BoxShadow(
                                color: colorScheme.primary.withValues(alpha: 0.2),
                                blurRadius: 16,
                                offset: const Offset(0, 4),
                              ),
                            ],
                          ),
                          child: Icon(
                            Icons.phone_forwarded_rounded,
                            size: 40,
                            color: colorScheme.primary,
                          ),
                        ),
                      ),
                      const SizedBox(height: 16),
                      Text(
                        'AutoCall',
                        textAlign: TextAlign.center,
                        style: theme.textTheme.headlineMedium?.copyWith(
                          fontWeight: FontWeight.bold,
                          color: colorScheme.primary,
                          letterSpacing: 0.5,
                        ),
                      ),
                      const SizedBox(height: 4),
                      Text(
                        'Easy Call Center & Auto Dialer',
                        textAlign: TextAlign.center,
                        style: theme.textTheme.bodyMedium?.copyWith(
                          color: theme.hintColor,
                          fontWeight: FontWeight.w500,
                        ),
                      ),
                    ],
                    SizedBox(height: isKeyboardOpen ? 12 : 32),

                    // Login Card
                    Card(
                      elevation: 2,
                      shape: RoundedRectangleBorder(
                        borderRadius: BorderRadius.circular(16),
                      ),
                      child: Padding(
                        padding: EdgeInsets.all(isKeyboardOpen ? 14.0 : 20.0),
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Text(
                              'Masuk ke Akun',
                              style: theme.textTheme.titleMedium?.copyWith(
                                fontWeight: FontWeight.bold,
                              ),
                            ),
                            const SizedBox(height: 4),
                            Text(
                              'Gunakan akun yang sudah terdaftar',
                              style: theme.textTheme.bodySmall?.copyWith(
                                color: theme.hintColor,
                              ),
                            ),
                            const SizedBox(height: 20),

                            // Error Message Banner
                            if (auth.errorMessage != null) ...[
                              Container(
                                padding: const EdgeInsets.all(12),
                                decoration: BoxDecoration(
                                  color: colorScheme.errorContainer,
                                  borderRadius: BorderRadius.circular(10),
                                  border: Border.all(
                                    color: colorScheme.error.withValues(
                                      alpha: 0.5,
                                    ),
                                  ),
                                ),
                                child: Row(
                                  crossAxisAlignment: CrossAxisAlignment.start,
                                  children: [
                                    Icon(
                                      Icons.error_outline,
                                      color: colorScheme.error,
                                      size: 20,
                                    ),
                                    const SizedBox(width: 8),
                                    Expanded(
                                      child: Text(
                                        auth.errorMessage!,
                                        style: TextStyle(
                                          fontSize: 12,
                                          color: colorScheme.onErrorContainer,
                                          height: 1.3,
                                        ),
                                      ),
                                    ),
                                    IconButton(
                                      icon: const Icon(Icons.close, size: 16),
                                      padding: EdgeInsets.zero,
                                      constraints: const BoxConstraints(),
                                      onPressed: () => auth.clearError(),
                                    ),
                                  ],
                                ),
                              ),
                              const SizedBox(height: 16),
                            ],

                            // Username Input
                            TextFormField(
                              controller: _usernameController,
                              keyboardType: TextInputType.text,
                              textInputAction: TextInputAction.next,
                              decoration: InputDecoration(
                                labelText: 'Username',
                                hintText: 'Masukkan username',
                                prefixIcon: const Icon(Icons.person_outline),
                                border: OutlineInputBorder(
                                  borderRadius: BorderRadius.circular(12),
                                ),
                                filled: true,
                                fillColor: theme.cardColor,
                              ),
                              validator: (val) {
                                if (val == null || val.trim().isEmpty) {
                                  return 'Username tidak boleh kosong';
                                }
                                return null;
                              },
                            ),
                            const SizedBox(height: 16),

                            // Password Input
                            TextFormField(
                              controller: _passwordController,
                              focusNode: _passwordFocusNode,
                              obscureText: _obscurePassword,
                              textInputAction: TextInputAction.done,
                              onFieldSubmitted: (_) => _handleLogin(),
                              decoration: InputDecoration(
                                labelText: 'Kata Sandi',
                                hintText: 'Masukkan kata sandi',
                                prefixIcon: const Icon(Icons.lock_outline),
                                suffixIcon: IconButton(
                                  icon: Icon(
                                    _obscurePassword
                                        ? Icons.visibility_off
                                        : Icons.visibility,
                                  ),
                                  onPressed: () {
                                    setState(() {
                                      _obscurePassword = !_obscurePassword;
                                    });
                                  },
                                ),
                                border: OutlineInputBorder(
                                  borderRadius: BorderRadius.circular(12),
                                ),
                                filled: true,
                                fillColor: theme.cardColor,
                              ),
                              validator: (val) {
                                if (val == null || val.trim().isEmpty) {
                                  return 'Kata sandi tidak boleh kosong';
                                }
                                return null;
                              },
                            ),
                            const SizedBox(height: 20),

                            // Submit Button
                            SizedBox(
                              width: double.infinity,
                              height: 48,
                              child: ElevatedButton(
                                style: ElevatedButton.styleFrom(
                                  backgroundColor: colorScheme.primary,
                                  foregroundColor: colorScheme.onPrimary,
                                  shape: RoundedRectangleBorder(
                                    borderRadius: BorderRadius.circular(12),
                                  ),
                                  elevation: 2,
                                ),
                                onPressed: auth.isLoading ? null : _handleLogin,
                                child: auth.isLoading
                                    ? const SizedBox(
                                        width: 22,
                                        height: 22,
                                        child: CircularProgressIndicator(
                                          strokeWidth: 2.5,
                                          color: Colors.white,
                                        ),
                                      )
                                    : const Row(
                                        mainAxisAlignment:
                                            MainAxisAlignment.center,
                                        children: [
                                          Icon(Icons.login, size: 20),
                                          SizedBox(width: 8),
                                          Text(
                                            'Masuk ke AutoCall',
                                            style: TextStyle(
                                              fontSize: 15,
                                              fontWeight: FontWeight.bold,
                                            ),
                                          ),
                                        ],
                                      ),
                              ),
                            ),
                          ],
                        ),
                      ),
                    ),

                    const SizedBox(height: 32),
                    Text(
                      'AutoCall v1.0 • Keamanan & Akses Terproteksi',
                      textAlign: TextAlign.center,
                      style: theme.textTheme.bodySmall?.copyWith(
                        color: theme.hintColor.withValues(alpha: 0.7),
                        fontSize: 11,
                      ),
                    ),
                  ],
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }
}
