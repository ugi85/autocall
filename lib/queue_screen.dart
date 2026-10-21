import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import 'call_queue_provider.dart';
import 'models/call_item.dart';
import 'widgets/audio_player_sheet.dart';
import 'widgets/recordings_library_dialog.dart';

class QueueScreen extends StatefulWidget {
  const QueueScreen({super.key});

  @override
  State<QueueScreen> createState() => _QueueScreenState();
}

class _QueueScreenState extends State<QueueScreen> with WidgetsBindingObserver {
  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) {
      // Re-verify permissions and accessibility status upon returning from Settings
      context.read<CallQueueProvider>().checkPermissions();
    }
  }

  @override
  Widget build(BuildContext context) {
    final provider = context.watch<CallQueueProvider>();
    final theme = Theme.of(context);
    final allSystemsReady =
        provider.permissionsStatus['ALL_GRANTED'] == true &&
        provider.isAccessibilityEnabled &&
        provider.canDrawOverlays;

    return Scaffold(
      appBar: AppBar(
        title: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Text(
              'AutoCall',
              style: TextStyle(fontWeight: FontWeight.bold, fontSize: 18),
            ),
            Text(
              'Easy Call Center',
              style: TextStyle(
                fontSize: 11,
                color: theme.colorScheme.onSurface.withValues(alpha: 0.7),
              ),
            ),
          ],
        ),
        actions: [
          IconButton(
            tooltip: 'Tarik Kontak API Database',
            icon: provider.isLoadingContactsFromApi
                ? const SizedBox(
                    width: 20,
                    height: 20,
                    child: CircularProgressIndicator(
                      strokeWidth: 2,
                      color: Colors.white,
                    ),
                  )
                : const Icon(Icons.cloud_download),
            onPressed: provider.isLoadingContactsFromApi
                ? null
                : () => _fetchContactsFromApiDirect(context, provider),
          ),
          IconButton(
            tooltip: 'Pustaka Rekaman HP',
            icon: const Icon(Icons.library_music),
            onPressed: () => RecordingsLibraryDialog.show(context),
          ),
          IconButton(
            tooltip: 'Status Izin & Aksesibilitas',
            icon: Icon(
              allSystemsReady ? Icons.verified_user : Icons.gpp_maybe,
              color: allSystemsReady ? Colors.green : Colors.amber,
            ),
            onPressed: () => _showPermissionsDialog(context, provider),
          ),
          IconButton(
            tooltip: 'Pengaturan & API',
            icon: const Icon(Icons.settings),
            onPressed: () => _showSettingsDialog(context, provider),
          ),
        ],
      ),
      body: Column(
        children: [
          // 1. Permission Warning Banner if missing
          if (provider.permissionsStatus['ALL_GRANTED'] != true)
            _buildPermissionWarningBanner(context, provider),

          // 2. Accessibility & Overlay Warning Banner (Tutup Otomatis Telepon)
          if (!provider.isAccessibilityEnabled || !provider.canDrawOverlays)
            _buildAccessibilityWarningBanner(context, provider),

          // 3. Metrics Summary
          _buildMetricsOverview(context, provider),

          // 4. Active Call Timeout Banner (Auto Hangup jika tidak angkat/sibuk)
          if (provider.isCallTimerRunning &&
              provider.currentProcessingId != null)
            _buildCallTimeoutBanner(context, provider),

          // 4. Cooldown / Active Call Status Banner
          if (provider.isInCooldown) _buildCooldownBanner(context, provider),

          // 5. Autocall Action Toolbar
          _buildControlToolbar(context, provider),

          const Divider(height: 1),

          // 6. Main Queue List
          Expanded(
            child: provider.queue.isEmpty
                ? _buildEmptyState(context, provider)
                : ListView.separated(
                    padding: const EdgeInsets.symmetric(
                      horizontal: 14,
                      vertical: 12,
                    ),
                    itemCount: provider.queue.length,
                    separatorBuilder: (_, _) => const SizedBox(height: 10),
                    itemBuilder: (context, index) {
                      final item = provider.queue[index];
                      return _buildCallCard(context, provider, item, index);
                    },
                  ),
          ),
        ],
      ),
      floatingActionButton: FloatingActionButton.extended(
        onPressed: () => _showAddNumberDialog(context, provider),
        icon: const Icon(Icons.person_add_alt_1),
        label: const Text('Tambah Nomor'),
      ),
    );
  }

  // =================== METRICS OVERVIEW ===================
  Widget _buildMetricsOverview(
    BuildContext context,
    CallQueueProvider provider,
  ) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
      color: Theme.of(context).colorScheme.surfaceContainerHighest
          .withValues(alpha: 0.3),
      child: Row(
        children: [
          _buildMetricTile(
            'Total',
            provider.totalCount.toString(),
            Colors.blueGrey,
          ),
          const SizedBox(width: 8),
          _buildMetricTile(
            'Pending',
            provider.pendingCount.toString(),
            Colors.blue,
          ),
          const SizedBox(width: 8),
          _buildMetricTile(
            'Sukses',
            provider.completedCount.toString(),
            Colors.green,
          ),
          const SizedBox(width: 8),
          _buildMetricTile(
            'Gagal/Sibuk',
            provider.failedCount.toString(),
            Colors.redAccent,
          ),
          const SizedBox(width: 8),
          _buildMetricTile(
            'Uploaded',
            provider.uploadedCount.toString(),
            Colors.teal,
          ),
        ],
      ),
    );
  }

  Widget _buildMetricTile(String label, String value, Color color) {
    return Expanded(
      child: Container(
        padding: const EdgeInsets.symmetric(vertical: 8, horizontal: 4),
        decoration: BoxDecoration(
          color: color.withValues(alpha: 0.1),
          borderRadius: BorderRadius.circular(8),
          border: Border.all(color: color.withValues(alpha: 0.3)),
        ),
        child: Column(
          children: [
            Text(
              value,
              style: TextStyle(
                fontSize: 16,
                fontWeight: FontWeight.bold,
                color: color,
              ),
            ),
            const SizedBox(height: 2),
            Text(
              label,
              style: TextStyle(
                fontSize: 10,
                fontWeight: FontWeight.w500,
                color: color.withValues(alpha: 0.9),
              ),
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
            ),
          ],
        ),
      ),
    );
  }

  // =================== COOLDOWN BANNER ===================
  Widget _buildCooldownBanner(
    BuildContext context,
    CallQueueProvider provider,
  ) {
    final progress =
        provider.cooldownSecondsRemaining / provider.cooldownTotalSeconds;

    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 10),
      color: Colors.amber.shade900.withValues(alpha: 0.15),
      child: Column(
        children: [
          Row(
            children: [
              const SizedBox(
                width: 18,
                height: 18,
                child: CircularProgressIndicator(strokeWidth: 2.5),
              ),
              const SizedBox(width: 12),
              Expanded(
                child: Text(
                  'Jeda panggilan: ${provider.cooldownSecondsRemaining} detik...',
                  style: const TextStyle(
                    fontWeight: FontWeight.bold,
                    fontSize: 13,
                  ),
                ),
              ),
              TextButton.icon(
                style: TextButton.styleFrom(
                  visualDensity: VisualDensity.compact,
                  foregroundColor: Colors.amber.shade800,
                ),
                onPressed: provider.skipCooldown,
                icon: const Icon(Icons.fast_forward, size: 16),
                label: const Text('Dial Sekarang'),
              ),
            ],
          ),
          const SizedBox(height: 6),
          LinearProgressIndicator(
            value: progress.clamp(0.0, 1.0),
            backgroundColor: Colors.amber.withValues(alpha: 0.2),
            valueColor: AlwaysStoppedAnimation<Color>(Colors.amber.shade700),
          ),
        ],
      ),
    );
  }

  // =================== CONTROL TOOLBAR ===================
  Widget _buildControlToolbar(
    BuildContext context,
    CallQueueProvider provider,
  ) {
    final isRunning = provider.isQueueRunning;
    final isPaused = provider.isPaused;

    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
      child: Row(
        children: [
          // Start / Resume Button
          Expanded(
            flex: 3,
            child: ElevatedButton.icon(
              style: ElevatedButton.styleFrom(
                backgroundColor: isRunning && !isPaused
                    ? Colors.grey.shade400
                    : Colors.green.shade600,
                foregroundColor: Colors.white,
                padding: const EdgeInsets.symmetric(vertical: 12),
                shape: RoundedRectangleBorder(
                  borderRadius: BorderRadius.circular(10),
                ),
              ),
              onPressed: isRunning && !isPaused
                  ? null
                  : () {
                      if (!provider.isAccessibilityEnabled) {
                        _showAccessibilityModal(context, provider);
                        return;
                      }
                      if (!provider.canDrawOverlays) {
                        _showOverlayModal(context, provider);
                        return;
                      }
                      if (isPaused) {
                        provider.resumeQueue();
                      } else {
                        provider.startQueue();
                      }
                    },
              icon: Icon(isPaused ? Icons.play_arrow : Icons.phone_forwarded),
              label: Text(
                isPaused ? 'Lanjutkan' : 'Mulai Autocall',
                style: const TextStyle(fontWeight: FontWeight.bold),
              ),
            ),
          ),
          const SizedBox(width: 8),

          // Pause Button
          if (isRunning && !isPaused)
            Expanded(
              flex: 2,
              child: OutlinedButton.icon(
                style: OutlinedButton.styleFrom(
                  foregroundColor: Colors.orange.shade800,
                  side: BorderSide(color: Colors.orange.shade800),
                  padding: const EdgeInsets.symmetric(vertical: 12),
                  shape: RoundedRectangleBorder(
                    borderRadius: BorderRadius.circular(10),
                  ),
                ),
                onPressed: provider.pauseQueue,
                icon: const Icon(Icons.pause),
                label: const Text('Pause'),
              ),
            ),

          if (isRunning) const SizedBox(width: 8),

          // Stop Button
          if (isRunning)
            Expanded(
              flex: 2,
              child: ElevatedButton.icon(
                style: ElevatedButton.styleFrom(
                  backgroundColor: Colors.red.shade600,
                  foregroundColor: Colors.white,
                  padding: const EdgeInsets.symmetric(vertical: 12),
                  shape: RoundedRectangleBorder(
                    borderRadius: BorderRadius.circular(10),
                  ),
                ),
                onPressed: provider.stopQueue,
                icon: const Icon(Icons.stop),
                label: const Text('Stop'),
              ),
            ),

          if (!isRunning)
            IconButton.filledTonal(
              tooltip: 'Ulangi Panggilan Gagal',
              onPressed: provider.failedCount > 0
                  ? () => provider.requeueAllFailed()
                  : null,
              icon: const Icon(Icons.replay),
            ),
        ],
      ),
    );
  }

  // =================== CALL CARD ITEM ===================
  Widget _buildCallCard(
    BuildContext context,
    CallQueueProvider provider,
    CallItem item,
    int index,
  ) {
    final statusColor = _getStatusColor(item.status);
    final isProcessing = item.id == provider.currentProcessingId;

    return Card(
      elevation: isProcessing ? 3 : 1,
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(12),
        side: BorderSide(
          color: isProcessing ? Colors.blue.shade400 : Colors.grey.shade200,
          width: isProcessing ? 2 : 1,
        ),
      ),
      child: Padding(
        padding: const EdgeInsets.all(12),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            // Top Row: Number, Name, Dial Button
            Row(
              children: [
                CircleAvatar(
                  radius: 14,
                  backgroundColor: statusColor.withValues(alpha: 0.15),
                  child: Text(
                    '${index + 1}',
                    style: TextStyle(
                      fontSize: 12,
                      fontWeight: FontWeight.bold,
                      color: statusColor,
                    ),
                  ),
                ),
                const SizedBox(width: 10),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        item.phoneNumber,
                        style: const TextStyle(
                          fontSize: 16,
                          fontWeight: FontWeight.bold,
                          letterSpacing: 0.5,
                        ),
                      ),
                      if (item.name.isNotEmpty)
                        Text(
                          item.name,
                          style: TextStyle(
                            fontSize: 12,
                            color: Colors.grey.shade600,
                          ),
                        ),
                    ],
                  ),
                ),
                _buildActionButtons(provider, item),
              ],
            ),
            const SizedBox(height: 10),

            // Middle Badges: Call Status, Duration, CallType
            Wrap(
              spacing: 6,
              runSpacing: 4,
              crossAxisAlignment: WrapCrossAlignment.center,
              children: [
                _buildCallStatusChip(item.status),
                if (isProcessing && provider.isCallTimerRunning)
                  Chip(
                    avatar: const Icon(
                      Icons.timer,
                      size: 14,
                      color: Colors.red,
                    ),
                    label: Text(
                      'Timeout: ${provider.callTimeoutRemaining}s',
                      style: const TextStyle(
                        fontWeight: FontWeight.bold,
                        color: Colors.red,
                        fontSize: 11,
                      ),
                    ),
                    backgroundColor: Colors.red.shade50,
                    visualDensity: VisualDensity.compact,
                    materialTapTargetSize: MaterialTapTargetSize.shrinkWrap,
                    padding: const EdgeInsets.symmetric(horizontal: 4),
                  ),
                if (item.durationSeconds > 0)
                  Chip(
                    avatar: const Icon(Icons.timer_outlined, size: 14),
                    label: Text(item.durationFormatted),
                    visualDensity: VisualDensity.compact,
                    materialTapTargetSize: MaterialTapTargetSize.shrinkWrap,
                    padding: const EdgeInsets.symmetric(horizontal: 4),
                  ),
                if (item.callType != null)
                  Chip(
                    label: Text(item.callType!),
                    visualDensity: VisualDensity.compact,
                    materialTapTargetSize: MaterialTapTargetSize.shrinkWrap,
                    padding: const EdgeInsets.symmetric(horizontal: 4),
                  ),
              ],
            ),
            const SizedBox(height: 8),

            // Bottom Section: Upload Recording Status
            _buildUploadStatusRow(item),

            // Audio Player Button if recording exists or call was completed
            if (item.recordingFilePath != null &&
                item.recordingFilePath!.isNotEmpty) ...[
              const SizedBox(height: 8),
              SizedBox(
                width: double.infinity,
                child: FilledButton.tonalIcon(
                  style: FilledButton.styleFrom(
                    backgroundColor: Colors.teal.shade50,
                    foregroundColor: Colors.teal.shade900,
                    side: BorderSide(color: Colors.teal.shade200),
                    shape: RoundedRectangleBorder(
                      borderRadius: BorderRadius.circular(8),
                    ),
                  ),
                  icon: const Icon(Icons.play_circle_fill, color: Colors.teal),
                  label: const Text(
                    'Putar Hasil Rekaman Panggilan',
                    style: TextStyle(fontWeight: FontWeight.bold),
                  ),
                  onPressed: () {
                    AudioPlayerSheet.show(
                      context,
                      filePath: item.recordingFilePath!,
                      title: item.phoneNumber,
                      subtitle: item.name.isNotEmpty
                          ? '${item.name} (${item.durationFormatted})'
                          : 'Durasi: ${item.durationFormatted}',
                    );
                  },
                ),
              ),
            ] else if (item.status == CallStatus.completed) ...[
              const SizedBox(height: 8),
              SizedBox(
                width: double.infinity,
                child: OutlinedButton.icon(
                  style: OutlinedButton.styleFrom(
                    visualDensity: VisualDensity.compact,
                    foregroundColor: Colors.teal.shade800,
                  ),
                  icon: const Icon(Icons.library_music, size: 16),
                  label: const Text('Cari Rekaman di Pustaka HP'),
                  onPressed: () => RecordingsLibraryDialog.show(context),
                ),
              ),
            ],
          ],
        ),
      ),
    );
  }

  Widget _buildActionButtons(CallQueueProvider provider, CallItem item) {
    return Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        if (item.id == provider.currentProcessingId &&
            provider.isCallTimerRunning)
          IconButton(
            icon: const Icon(Icons.call_end, size: 22, color: Colors.red),
            tooltip: 'Tutup Panggilan Sekarang',
            onPressed: () => provider.hangupCurrentCall(),
          )
        else if (item.status != CallStatus.dialing &&
            item.status != CallStatus.inCall)
          IconButton(
            icon: const Icon(Icons.phone, size: 20, color: Colors.green),
            tooltip: 'Panggil Manual',
            onPressed: () {
              provider.requeueCall(item.id);
              provider.startQueue();
            },
          ),
        PopupMenuButton<String>(
          icon: const Icon(Icons.more_vert, size: 18),
          onSelected: (value) {
            if (value == 'requeue') {
              provider.requeueCall(item.id);
            } else if (value == 'delete') {
              provider.removeNumber(item.id);
            }
          },
          itemBuilder: (context) => [
            const PopupMenuItem(
              value: 'requeue',
              child: Row(
                children: [
                  Icon(Icons.replay, size: 18),
                  SizedBox(width: 8),
                  Text('Kembalikan ke Antrean'),
                ],
              ),
            ),
            const PopupMenuItem(
              value: 'delete',
              child: Row(
                children: [
                  Icon(Icons.delete_outline, size: 18, color: Colors.red),
                  SizedBox(width: 8),
                  Text('Hapus Nomor', style: TextStyle(color: Colors.red)),
                ],
              ),
            ),
          ],
        ),
      ],
    );
  }

  Widget _buildCallStatusChip(CallStatus status) {
    Color bg;
    Color fg;
    String label;
    IconData icon;

    switch (status) {
      case CallStatus.pending:
        bg = Colors.grey.shade100;
        fg = Colors.grey.shade700;
        label = 'PENDING';
        icon = Icons.schedule;
        break;
      case CallStatus.dialing:
        bg = Colors.amber.shade100;
        fg = Colors.amber.shade900;
        label = 'DIALING...';
        icon = Icons.ring_volume;
        break;
      case CallStatus.inCall:
        bg = Colors.blue.shade100;
        fg = Colors.blue.shade900;
        label = 'TERHUBUNG';
        icon = Icons.phone_in_talk;
        break;
      case CallStatus.completed:
        bg = Colors.green.shade100;
        fg = Colors.green.shade900;
        label = 'SELESAI';
        icon = Icons.check_circle_outline;
        break;
      case CallStatus.failedOrBusy:
        bg = Colors.red.shade100;
        fg = Colors.red.shade900;
        label = 'GAGAL / SIBUK';
        icon = Icons.phone_missed;
        break;
    }

    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
      decoration: BoxDecoration(
        color: bg,
        borderRadius: BorderRadius.circular(6),
        border: Border.all(color: fg.withValues(alpha: 0.3)),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(icon, size: 12, color: fg),
          const SizedBox(width: 4),
          Text(
            label,
            style: TextStyle(
              fontSize: 11,
              fontWeight: FontWeight.bold,
              color: fg,
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildUploadStatusRow(CallItem item) {
    Color color;
    IconData icon;
    String statusTitle;

    switch (item.uploadStatus) {
      case UploadStatus.idle:
        if (item.status == CallStatus.completed) {
          color = Colors.grey;
          icon = Icons.cloud_queue;
          statusTitle = 'Menunggu pencarian audio OEM...';
        } else {
          return const SizedBox.shrink();
        }
        break;
      case UploadStatus.waitingRecording:
        color = Colors.blue;
        icon = Icons.hourglass_top;
        statusTitle = 'Scanning direktori rekaman OEM...';
        break;
      case UploadStatus.uploading:
        color = Colors.orange;
        icon = Icons.cloud_upload_outlined;
        statusTitle = 'Mengunggah rekaman (OkHttp WorkManager)...';
        break;
      case UploadStatus.uploaded:
        color = Colors.teal;
        icon = Icons.cloud_done;
        statusTitle = 'Rekaman berhasil diunggah ke backend';
        break;
      case UploadStatus.failed:
        color = Colors.red;
        icon = Icons.error_outline;
        statusTitle = item.uploadMessage ?? 'Gagal upload rekaman';
        break;
    }

    return Container(
      width: double.infinity,
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
      decoration: BoxDecoration(
        color: color.withValues(alpha: 0.08),
        borderRadius: BorderRadius.circular(8),
      ),
      child: Row(
        children: [
          Icon(icon, size: 14, color: color),
          const SizedBox(width: 6),
          Expanded(
            child: Text(
              statusTitle,
              style: TextStyle(
                fontSize: 11,
                color: color,
                fontWeight: FontWeight.w500,
              ),
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
            ),
          ),
          if (item.uploadStatus == UploadStatus.uploading)
            const SizedBox(
              width: 12,
              height: 12,
              child: CircularProgressIndicator(strokeWidth: 2),
            ),
        ],
      ),
    );
  }

  Color _getStatusColor(CallStatus status) {
    switch (status) {
      case CallStatus.pending:
        return Colors.blueGrey;
      case CallStatus.dialing:
      case CallStatus.inCall:
        return Colors.orange;
      case CallStatus.completed:
        return Colors.green;
      case CallStatus.failedOrBusy:
        return Colors.red;
    }
  }

  // =================== WARNING & DIALOGS ===================
  Widget _buildPermissionWarningBanner(
    BuildContext context,
    CallQueueProvider provider,
  ) {
    return MaterialBanner(
      backgroundColor: Colors.amber.shade50,
      leading: const Icon(Icons.warning_amber_rounded, color: Colors.amber),
      content: const Text(
        'Izin telepon atau akses direktori rekaman OEM belum lengkap.',
        style: TextStyle(fontSize: 12, fontWeight: FontWeight.w600),
      ),
      actions: [
        TextButton(
          onPressed: () => _showPermissionsDialog(context, provider),
          child: const Text('Periksa Izin'),
        ),
      ],
    );
  }

  Widget _buildAccessibilityWarningBanner(
    BuildContext context,
    CallQueueProvider provider,
  ) {
    if (!provider.isAccessibilityEnabled) {
      return MaterialBanner(
        backgroundColor: Colors.deepOrange.shade50,
        leading: Icon(Icons.touch_app, color: Colors.deepOrange.shade700),
        content: Text(
          'Layanan Tutup Otomatis Belum Aktif: aktifkan AutoCall Assistant di menu Aksesibilitas.',
          style: TextStyle(
            fontSize: 12,
            fontWeight: FontWeight.w600,
            color: Colors.deepOrange.shade900,
          ),
        ),
        actions: [
          ElevatedButton(
            style: ElevatedButton.styleFrom(
              backgroundColor: Colors.deepOrange.shade700,
              foregroundColor: Colors.white,
              visualDensity: VisualDensity.compact,
              padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 4),
            ),
            onPressed: () => provider.openAccessibilitySettings(),
            child: const Text('Aktifkan Sekarang'),
          ),
        ],
      );
    } else if (!provider.canDrawOverlays) {
      return MaterialBanner(
        backgroundColor: Colors.blue.shade50,
        leading: Icon(
          Icons.picture_in_picture_alt,
          color: Colors.blue.shade700,
        ),
        content: Text(
          'Izin Floating Widget Belum Aktif: Izinkan AutoCall menampilkan widget hitung mundur melayang di atas layar telepon.',
          style: TextStyle(
            fontSize: 12,
            fontWeight: FontWeight.w600,
            color: Colors.blue.shade900,
          ),
        ),
        actions: [
          ElevatedButton(
            style: ElevatedButton.styleFrom(
              backgroundColor: Colors.blue.shade700,
              foregroundColor: Colors.white,
              visualDensity: VisualDensity.compact,
              padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 4),
            ),
            onPressed: () => provider.requestOverlayPermission(),
            child: const Text('Izinkan Overlay'),
          ),
        ],
      );
    }
    return const SizedBox.shrink();
  }

  Widget _buildEmptyState(BuildContext context, CallQueueProvider provider) {
    return Center(
      child: Column(
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          Icon(Icons.playlist_add_check, size: 64, color: Colors.grey.shade400),
          const SizedBox(height: 12),
          const Text(
            'Antrean Panggilan Kosong',
            style: TextStyle(fontSize: 16, fontWeight: FontWeight.bold),
          ),
          const SizedBox(height: 6),
          Text(
            'Tambahkan nomor telepon untuk memulai autocall sekuensial.',
            style: TextStyle(color: Colors.grey.shade600, fontSize: 13),
          ),
          const SizedBox(height: 16),
          Wrap(
            spacing: 10,
            runSpacing: 10,
            alignment: WrapAlignment.center,
            children: [
              ElevatedButton.icon(
                onPressed: () => _showAddNumberDialog(context, provider),
                icon: const Icon(Icons.add),
                label: const Text('Tambah Nomor'),
              ),
              OutlinedButton.icon(
                onPressed: provider.isLoadingContactsFromApi
                    ? null
                    : () => _fetchContactsFromApiDirect(context, provider),
                icon: const Icon(Icons.cloud_download),
                label: const Text('Tarik dari API'),
              ),
            ],
          ),
        ],
      ),
    );
  }

  void _showAddNumberDialog(BuildContext context, CallQueueProvider provider) {
    final singlePhoneController = TextEditingController();
    final singleNameController = TextEditingController();
    final batchController = TextEditingController();
    final apiFetchUrlController = TextEditingController(
      text: provider.contactsApiUrl,
    );
    bool clearExistingQueue = false;
    bool isFetchingApi = false;

    showModalBottomSheet(
      context: context,
      isScrollControlled: true,
      useSafeArea: true,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(20)),
      ),
      builder: (ctx) {
        return StatefulBuilder(
          builder: (ctx, setModalState) {
            final mediaQuery = MediaQuery.of(ctx);
            final bottomInset = mediaQuery.viewInsets.bottom;
            final availableHeight =
                mediaQuery.size.height -
                mediaQuery.padding.top -
                mediaQuery.padding.bottom;
            // Calculate dynamic height adapting seamlessly when keyboard opens or closes
            final sheetHeight = ((availableHeight - bottomInset) * 0.92).clamp(
              280.0,
              540.0,
            );

            return Padding(
              padding: EdgeInsets.only(bottom: bottomInset),
              child: SizedBox(
                height: sheetHeight,
                child: DefaultTabController(
                  length: 3,
                  child: Padding(
                    padding: const EdgeInsets.fromLTRB(20, 12, 20, 12),
                    child: Column(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        // Drag handle
                        Center(
                          child: Container(
                            width: 36,
                            height: 4,
                            margin: const EdgeInsets.only(bottom: 12),
                            decoration: BoxDecoration(
                              color: Colors.grey.shade400,
                              borderRadius: BorderRadius.circular(2),
                            ),
                          ),
                        ),
                        const Text(
                          'Tambah Nomor ke Antrean',
                          style: TextStyle(
                            fontSize: 18,
                            fontWeight: FontWeight.bold,
                          ),
                        ),
                        const SizedBox(height: 12),
                        const TabBar(
                          isScrollable: true,
                          tabAlignment: TabAlignment.center,
                          tabs: [
                            Tab(
                              icon: Icon(Icons.person_add),
                              text: 'Satu Nomor',
                            ),
                            Tab(
                              icon: Icon(Icons.playlist_add),
                              text: 'Batch / Paste',
                            ),
                            Tab(
                              icon: Icon(Icons.cloud_download),
                              text: 'Tarik API Database',
                            ),
                          ],
                        ),
                        const SizedBox(height: 12),
                        Expanded(
                          child: TabBarView(
                            children: [
                              // Tab 1: Single Number
                              SingleChildScrollView(
                                padding: const EdgeInsets.only(
                                  top: 8,
                                  bottom: 8,
                                ),
                                child: Column(
                                  children: [
                                    TextField(
                                      controller: singlePhoneController,
                                      keyboardType: TextInputType.phone,
                                      textInputAction: TextInputAction.next,
                                      decoration: const InputDecoration(
                                        labelText: 'Nomor Telepon',
                                        hintText: 'Contoh: 081234567890',
                                        prefixIcon: Icon(Icons.phone),
                                        border: OutlineInputBorder(),
                                      ),
                                    ),
                                    const SizedBox(height: 12),
                                    TextField(
                                      controller: singleNameController,
                                      textInputAction: TextInputAction.done,
                                      decoration: const InputDecoration(
                                        labelText: 'Nama / Label (Opsional)',
                                        prefixIcon: Icon(Icons.person),
                                        border: OutlineInputBorder(),
                                      ),
                                    ),
                                    const SizedBox(height: 16),
                                    ElevatedButton.icon(
                                      style: ElevatedButton.styleFrom(
                                        minimumSize: const Size.fromHeight(48),
                                        backgroundColor: Theme.of(context)
                                            .primaryColor,
                                        foregroundColor: Colors.white,
                                      ),
                                      onPressed: () {
                                        if (singlePhoneController.text
                                            .trim()
                                            .isNotEmpty) {
                                          provider.addNumber(
                                            singlePhoneController.text.trim(),
                                            singleNameController.text.trim(),
                                          );
                                        }
                                        Navigator.pop(ctx);
                                      },
                                      icon: const Icon(Icons.check),
                                      label: const Text('Simpan ke Antrean'),
                                    ),
                                  ],
                                ),
                              ),
                              // Tab 2: Batch Paste
                              SingleChildScrollView(
                                padding: const EdgeInsets.only(
                                  top: 8,
                                  bottom: 8,
                                ),
                                child: Column(
                                  children: [
                                    TextField(
                                      controller: batchController,
                                      maxLines: 4,
                                      keyboardType: TextInputType.multiline,
                                      decoration: const InputDecoration(
                                        hintText: 'Paste nomor disini (satu nomor per baris):\n081234567890\n085712345678, Budi\n081998877665, Siti',
                                        border: OutlineInputBorder(),
                                      ),
                                    ),
                                    const SizedBox(height: 14),
                                    ElevatedButton.icon(
                                      style: ElevatedButton.styleFrom(
                                        minimumSize: const Size.fromHeight(48),
                                        backgroundColor: Theme.of(context)
                                            .primaryColor,
                                        foregroundColor: Colors.white,
                                      ),
                                      onPressed: () {
                                        if (batchController.text
                                            .trim()
                                            .isNotEmpty) {
                                          final lines = batchController.text
                                              .split('\n');
                                          provider.addBatchNumbers(lines);
                                        }
                                        Navigator.pop(ctx);
                                      },
                                      icon: const Icon(Icons.library_add_check),
                                      label: const Text(
                                        'Simpan Semua ke Antrean',
                                      ),
                                    ),
                                  ],
                                ),
                              ),
                              // Tab 3: Pull from Database API
                              SingleChildScrollView(
                                padding: const EdgeInsets.only(
                                  top: 8,
                                  bottom: 8,
                                ),
                                child: Column(
                                  crossAxisAlignment: CrossAxisAlignment.start,
                                  children: [
                                    TextField(
                                      controller: apiFetchUrlController,
                                      decoration: InputDecoration(
                                        labelText: 'URL API Database Kontak',
                                        hintText: 'https://domain.com/api/v1/contacts',
                                        prefixIcon: const Icon(
                                          Icons.cloud_download,
                                        ),
                                        suffixIcon: IconButton(
                                          icon: const Icon(Icons.clear),
                                          onPressed: () =>
                                              apiFetchUrlController.clear(),
                                        ),
                                        border: const OutlineInputBorder(),
                                      ),
                                    ),
                                    _buildApiGuideCard(context),
                                    CheckboxListTile(
                                      dense: true,
                                      contentPadding: EdgeInsets.zero,
                                      title: const Text(
                                        'Gantikan / kosongkan antrean lama',
                                        style: TextStyle(fontSize: 13),
                                      ),
                                      value: clearExistingQueue,
                                      onChanged: (val) {
                                        setModalState(() {
                                          clearExistingQueue = val ?? false;
                                        });
                                      },
                                    ),
                                    const SizedBox(height: 8),
                                    ElevatedButton.icon(
                                      style: ElevatedButton.styleFrom(
                                        minimumSize: const Size.fromHeight(48),
                                        backgroundColor: Colors.teal,
                                        foregroundColor: Colors.white,
                                      ),
                                      onPressed: isFetchingApi
                                          ? null
                                          : () async {
                                              final url = apiFetchUrlController
                                                  .text
                                                  .trim();
                                              if (url.isEmpty) {
                                                ScaffoldMessenger.of(context)
                                                    .showSnackBar(
                                                      const SnackBar(
                                                        content: Text(
                                                          'Harap masukkan URL API database',
                                                        ),
                                                      ),
                                                    );
                                                return;
                                              }
                                              setModalState(
                                                () => isFetchingApi = true,
                                              );
                                              try {
                                                await provider
                                                    .setContactsApiUrl(url);
                                                final count = await provider
                                                    .fetchContactsFromApi(
                                                      overrideUrl: url,
                                                      clearExisting:
                                                          clearExistingQueue,
                                                    );
                                                if (ctx.mounted) {
                                                  Navigator.pop(ctx);
                                                }
                                                if (context.mounted) {
                                                  ScaffoldMessenger.of(
                                                    context,
                                                  ).showSnackBar(
                                                    SnackBar(
                                                      content: Text(
                                                        'Berhasil memuat $count kontak dari API!',
                                                      ),
                                                      backgroundColor:
                                                          Colors.green,
                                                    ),
                                                  );
                                                }
                                              } catch (e) {
                                                setModalState(
                                                  () => isFetchingApi = false,
                                                );
                                                if (context.mounted) {
                                                  ScaffoldMessenger.of(
                                                    context,
                                                  ).showSnackBar(
                                                    SnackBar(
                                                      content: Text(
                                                        'Gagal menarik kontak: $e',
                                                      ),
                                                      backgroundColor:
                                                          Colors.red,
                                                    ),
                                                  );
                                                }
                                              }
                                            },
                                      icon: isFetchingApi
                                          ? const SizedBox(
                                              width: 18,
                                              height: 18,
                                              child: CircularProgressIndicator(
                                                strokeWidth: 2,
                                                color: Colors.white,
                                              ),
                                            )
                                          : const Icon(Icons.download),
                                      label: Text(
                                        isFetchingApi
                                            ? 'Menghubungi API...'
                                            : 'Tarik Kontak dari API Database',
                                      ),
                                    ),
                                  ],
                                ),
                              ),
                            ],
                          ),
                        ),
                      ],
                    ),
                  ),
                ),
              ),
            );
          },
        );
      },
    );
  }

  void _showPermissionsDialog(
    BuildContext context,
    CallQueueProvider provider,
  ) {
    showDialog(
      context: context,
      builder: (ctx) {
        return StatefulBuilder(
          builder: (ctx, setState) {
            final perms = provider.permissionsStatus;
            return AlertDialog(
              title: const Text('Status Izin Sistem HP'),
              content: SingleChildScrollView(
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    _buildPermissionTile(
                      'Panggilan Telepon (CALL_PHONE)',
                      perms['CALL_PHONE'] == true,
                      'Wajib untuk mengeksekusi dial otomatis tanpa dialog',
                      provider.requestRuntimePermissions,
                    ),
                    _buildPermissionTile(
                      'Status & State Telepon (READ_PHONE_STATE)',
                      perms['READ_PHONE_STATE'] == true,
                      'Mendeteksi event OFFHOOK -> IDLE',
                      provider.requestRuntimePermissions,
                    ),
                    _buildPermissionTile(
                      'Riwayat Panggilan (READ_CALL_LOG)',
                      perms['READ_CALL_LOG'] == true,
                      'Membaca durasi & timestamp aktual panggilan',
                      provider.requestRuntimePermissions,
                    ),
                    _buildPermissionTile(
                      'Akses Semua File (OEM Call Rec)',
                      perms['MANAGE_EXTERNAL_STORAGE'] == true,
                      'Membaca folder rekaman device phone',
                      provider.requestAllFilesAccess,
                    ),
                    _buildPermissionTile(
                      'Bebaskan Optimasi Baterai',
                      perms['IGNORE_BATTERY_OPTIMIZATIONS'] == true,
                      'Menjaga WorkManager & listener tidak dimatikan OS',
                      provider.requestIgnoreBatteryOptimizations,
                    ),
                    const Divider(height: 16),
                    _buildPermissionTile(
                      'Layanan Tutup Otomatis (Aksesibilitas)',
                      provider.isAccessibilityEnabled,
                      'Menutup panggilan otomatis saat timeout tanpa manual',
                      provider.openAccessibilitySettings,
                    ),
                    _buildPermissionTile(
                      'Tampilkan di Atas Aplikasi Lain (Floating Widget)',
                      provider.canDrawOverlays,
                      'Menampilkan widget hitung mundur & tombol Tutup di atas layar telepon ',
                      provider.requestOverlayPermission,
                    ),
                  ],
                ),
              ),
              actions: [
                TextButton(
                  onPressed: () async {
                    await provider.checkPermissions();
                    setState(() {});
                  },
                  child: const Text('Refresh Status'),
                ),
                TextButton(
                  onPressed: () => Navigator.pop(ctx),
                  child: const Text('Tutup'),
                ),
              ],
            );
          },
        );
      },
    );
  }

  Widget _buildPermissionTile(
    String title,
    bool isGranted,
    String subtitle,
    VoidCallback onGrant,
  ) {
    return ListTile(
      dense: true,
      contentPadding: EdgeInsets.zero,
      leading: Icon(
        isGranted ? Icons.check_circle : Icons.cancel,
        color: isGranted ? Colors.green : Colors.red,
      ),
      title: Text(title, style: const TextStyle(fontWeight: FontWeight.bold)),
      subtitle: Text(subtitle, style: const TextStyle(fontSize: 11)),
      trailing: !isGranted
          ? OutlinedButton(
              onPressed: onGrant,
              style: OutlinedButton.styleFrom(
                visualDensity: VisualDensity.compact,
              ),
              child: const Text('Izinkan'),
            )
          : null,
    );
  }

  void _showAccessibilityModal(
    BuildContext context,
    CallQueueProvider provider,
  ) {
    showDialog(
      context: context,
      builder: (ctx) => AlertDialog(
        icon: const Icon(Icons.touch_app, size: 48, color: Colors.deepOrange),
        title: const Text(
          'Aktifkan Autocall Assistant',
          textAlign: TextAlign.center,
          style: TextStyle(fontWeight: FontWeight.bold),
        ),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Text(
              'Agar HP dapat menutup panggilan otomatis saat durasi timeout habis tanpa perlu Anda tutup manual (dan fitur auto rekam panggilan bawaan HP aktifkan), aktifkan izin Aksesibilitas berikut:',
              style: TextStyle(fontSize: 13),
            ),
            const SizedBox(height: 12),
            Container(
              padding: const EdgeInsets.all(12),
              decoration: BoxDecoration(
                color: Colors.deepOrange.shade50,
                borderRadius: BorderRadius.circular(10),
                border: Border.all(color: Colors.deepOrange.shade200),
              ),
              child: const Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    'Langkah Mudah di Pengaturan HP:',
                    style: TextStyle(fontWeight: FontWeight.bold, fontSize: 12),
                  ),
                  SizedBox(height: 6),
                  Text(
                    '1. Ketuk tombol "Buka Pengaturan Aksesibilitas" di bawah.',
                    style: TextStyle(fontSize: 12),
                  ),
                  Text(
                    '2. Pilih "Aplikasi yang didownload" (AutoCall).',
                    style: TextStyle(fontSize: 12),
                  ),
                  Text(
                    '3. Pilih "AutoCall", lalu nyalakan sakelarnya.',
                    style: TextStyle(fontSize: 12, fontWeight: FontWeight.w600),
                  ),
                ],
              ),
            ),
          ],
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx),
            child: const Text('Nanti Saja'),
          ),
          ElevatedButton.icon(
            style: ElevatedButton.styleFrom(
              backgroundColor: Colors.deepOrange.shade700,
              foregroundColor: Colors.white,
            ),
            onPressed: () {
              Navigator.pop(ctx);
              provider.openAccessibilitySettings();
            },
            icon: const Icon(Icons.open_in_new, size: 16),
            label: const Text('Buka Pengaturan Aksesibilitas'),
          ),
        ],
      ),
    );
  }

  void _showOverlayModal(BuildContext context, CallQueueProvider provider) {
    showDialog(
      context: context,
      builder: (ctx) => AlertDialog(
        icon: const Icon(
          Icons.picture_in_picture_alt,
          size: 48,
          color: Colors.blue,
        ),
        title: const Text(
          'Izin Tampilan di Atas Aplikasi',
          textAlign: TextAlign.center,
          style: TextStyle(fontWeight: FontWeight.bold),
        ),
        content: const Text(
          'Izinkan AutoCall menampilkan widget hitung mundur melayang di atas layar telepon agar proses autocall tidak dibekukan oleh sistem.',
          style: TextStyle(fontSize: 13),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx),
            child: const Text('Batal'),
          ),
          ElevatedButton(
            style: ElevatedButton.styleFrom(
              backgroundColor: Colors.blue.shade700,
              foregroundColor: Colors.white,
            ),
            onPressed: () {
              Navigator.pop(ctx);
              provider.requestOverlayPermission();
            },
            child: const Text('Izinkan Overlay'),
          ),
        ],
      ),
    );
  }

  void _showSettingsDialog(BuildContext context, CallQueueProvider provider) {
    final uploadUrlController = TextEditingController(
      text: provider.backendUrl,
    );
    final contactsUrlController = TextEditingController(
      text: provider.contactsApiUrl,
    );
    int currentDelay = provider.cooldownTotalSeconds;
    int currentTimeout = provider.callTimeoutSeconds;

    showDialog(
      context: context,
      builder: (ctx) {
        return StatefulBuilder(
          builder: (ctx, setState) {
            return AlertDialog(
              title: const Text('Pengaturan Autocall'),
              content: SingleChildScrollView(
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    // 1. Batas Durasi Tunggu Panggilan (Timeout)
                    const Text(
                      'Batas Waktu Tunggu Panggilan:',
                      style: TextStyle(fontWeight: FontWeight.bold),
                    ),
                    Slider(
                      value: currentTimeout.toDouble(),
                      min: 10,
                      max: 60,
                      divisions: 10,
                      label: '$currentTimeout detik',
                      onChanged: (val) {
                        setState(() {
                          currentTimeout = val.toInt();
                        });
                      },
                    ),
                    Center(
                      child: Text(
                        '$currentTimeout detik (Otomatis tutup jika tak diangkat/sibuk)',
                        style: const TextStyle(
                          fontSize: 12,
                          color: Colors.redAccent,
                          fontWeight: FontWeight.w500,
                        ),
                      ),
                    ),
                    const SizedBox(height: 16),

                    // 2. Jeda Aman Antar Panggilan
                    const Text(
                      'Jeda Aman Antar Panggilan (Detik):',
                      style: TextStyle(fontWeight: FontWeight.bold),
                    ),
                    Slider(
                      value: currentDelay.toDouble(),
                      min: 5,
                      max: 15,
                      divisions: 10,
                      label: '$currentDelay detik',
                      onChanged: (val) {
                        setState(() {
                          currentDelay = val.toInt();
                        });
                      },
                    ),
                    Center(
                      child: Text(
                        '$currentDelay detik (Rekomendasi OEM: 5-8s)',
                        style: const TextStyle(
                          fontSize: 12,
                          color: Colors.blue,
                        ),
                      ),
                    ),
                    const SizedBox(height: 16),

                    // 3. API Daftar Kontak Database
                    const Text(
                      'URL API Daftar Kontak :',
                      style: TextStyle(fontWeight: FontWeight.bold),
                    ),
                    const SizedBox(height: 6),
                    TextField(
                      controller: contactsUrlController,
                      decoration: InputDecoration(
                        hintText: 'https://domain.com/api/v1/contacts',
                        prefixIcon: const Icon(Icons.contacts),
                        suffixIcon: IconButton(
                          icon: const Icon(Icons.clear),
                          onPressed: () => contactsUrlController.clear(),
                        ),
                        border: const OutlineInputBorder(),
                      ),
                    ),
                    _buildApiGuideCard(context),
                    const SizedBox(height: 16),

                    // 4. Backend Upload API
                    const Text(
                      'Endpoint Backend Upload Rekaman API:',
                      style: TextStyle(fontWeight: FontWeight.bold),
                    ),
                    const SizedBox(height: 6),
                    TextField(
                      controller: uploadUrlController,
                      decoration: const InputDecoration(
                        hintText:
                            'https://backend.com/api/v1/recordings/upload',
                        prefixIcon: Icon(Icons.cloud_upload),
                        border: OutlineInputBorder(),
                      ),
                    ),
                  ],
                ),
              ),
              actions: [
                TextButton(
                  onPressed: () => Navigator.pop(ctx),
                  child: const Text('Batal'),
                ),
                ElevatedButton(
                  onPressed: () {
                    provider.setCallTimeoutSeconds(currentTimeout);
                    provider.setCooldownSeconds(currentDelay);
                    provider.setBackendUrl(uploadUrlController.text.trim());
                    provider.setContactsApiUrl(
                      contactsUrlController.text.trim(),
                    );
                    Navigator.pop(ctx);
                  },
                  child: const Text('Simpan'),
                ),
              ],
            );
          },
        );
      },
    );
  }

  Future<void> _fetchContactsFromApiDirect(
    BuildContext context,
    CallQueueProvider provider,
  ) async {
    final messenger = ScaffoldMessenger.of(context);

    // Validate if URL is configured in settings
    if (provider.contactsApiUrl.trim().isEmpty) {
      messenger.hideCurrentSnackBar();
      messenger.showSnackBar(
        SnackBar(
          content: const Text(
            'URL API belum diatur. Masukkan URL API database Anda di menu Pengaturan.',
          ),
          backgroundColor: Colors.orange.shade800,
          duration: const Duration(seconds: 5),
          action: SnackBarAction(
            label: 'Atur URL',
            textColor: Colors.white,
            onPressed: () => _showSettingsDialog(context, provider),
          ),
        ),
      );
      return;
    }

    messenger.showSnackBar(
      const SnackBar(
        content: Row(
          children: [
            SizedBox(
              width: 16,
              height: 16,
              child: CircularProgressIndicator(
                strokeWidth: 2,
                color: Colors.white,
              ),
            ),
            SizedBox(width: 12),
            Text('Menghubungi API database kontak...'),
          ],
        ),
        duration: Duration(seconds: 2),
      ),
    );

    try {
      final count = await provider.fetchContactsFromApi();
      messenger.hideCurrentSnackBar();
      messenger.showSnackBar(
        SnackBar(
          content: Text('Berhasil mengambil $count kontak dari database!'),
          backgroundColor: Colors.green,
          duration: const Duration(seconds: 4),
        ),
      );
    } catch (e) {
      messenger.hideCurrentSnackBar();
      messenger.showSnackBar(
        SnackBar(
          content: Text('Gagal menarik kontak API: $e'),
          backgroundColor: Colors.red,
          duration: const Duration(seconds: 5),
          action: SnackBarAction(
            label: 'Atur URL',
            textColor: Colors.white,
            onPressed: () => _showSettingsDialog(context, provider),
          ),
        ),
      );
    }
  }

  /// Information card detailing supported API column headers & JSON formats
  Widget _buildApiGuideCard(BuildContext context) {
    return Container(
      // margin: const EdgeInsets.symmetric(vertical: 8),
      // padding: const EdgeInsets.all(12),
      // decoration: BoxDecoration(
      //   color: Colors.blue.shade50,
      //   borderRadius: BorderRadius.circular(10),
      //   border: Border.all(color: Colors.blue.shade200),
      // ),
      // child: Column(
      //   crossAxisAlignment: CrossAxisAlignment.start,
      //   children: [
      //     Row(
      //       children: [
      //         Icon(Icons.info_outline, size: 18, color: Colors.blue.shade800),
      //         const SizedBox(width: 6),
      //         Text(
      //           'Panduan Header / Nama Kolom API',
      //           style: TextStyle(
      //             fontWeight: FontWeight.bold,
      //             fontSize: 12,
      //             color: Colors.blue.shade900,
      //           ),
      //         ),
      //       ],
      //     ),
      //     const SizedBox(height: 6),
      //     const Text(
      //       'Agar kontak terbaca otomatis dan tidak gagal, gunakan salah satu penamaan header kolom berikut pada API / Google Sheets Anda:',
      //       style: TextStyle(fontSize: 11, height: 1.3),
      //     ),
      //     const SizedBox(height: 6),
      //     // Kolom Telepon
      //     RichText(
      //       text: TextSpan(
      //         style: const TextStyle(fontSize: 11, color: Colors.black87),
      //         children: [
      //           const TextSpan(
      //             text: '• Kolom Nomor HP: ',
      //             style: TextStyle(fontWeight: FontWeight.bold),
      //           ),
      //           TextSpan(
      //             text: 'No_Tlp, phone, phoneNumber, nomor, no_hp, telepon, telp, mobile',
      //             style: TextStyle(
      //               fontFamily: 'monospace',
      //               color: Colors.blue.shade900,
      //               fontWeight: FontWeight.w600,
      //             ),
      //           ),
      //         ],
      //       ),
      //     ),
      //     const SizedBox(height: 4),
      //     // Kolom Nama
      //     RichText(
      //       text: TextSpan(
      //         style: const TextStyle(fontSize: 11, color: Colors.black87),
      //         children: [
      //           const TextSpan(
      //             text: '• Kolom Nama: ',
      //             style: TextStyle(fontWeight: FontWeight.bold),
      //           ),
      //           TextSpan(
      //             text: 'Name, nama, name, full_name, customer_name, client',
      //             style: TextStyle(
      //               fontFamily: 'monospace',
      //               color: Colors.blue.shade900,
      //               fontWeight: FontWeight.w600,
      //             ),
      //           ),
      //         ],
      //       ),
      //     ),
      //     const SizedBox(height: 4),
      //     // Format JSON
      //     const Text(
      //       '• Format JSON: Mendukung Array [...] atau Objek {"data": [...]}',
      //       style: TextStyle(fontSize: 11, color: Colors.black87),
      //     ),
      //   ],
      // ),
    );
  }

  /// Banner indikator panggilan aktif dengan hitung mundur timeout auto-hangup
  Widget _buildCallTimeoutBanner(
    BuildContext context,
    CallQueueProvider provider,
  ) {
    final activeItem = provider.queue.firstWhere(
      (item) => item.id == provider.currentProcessingId,
      orElse: () => provider.queue.first,
    );
    final remaining = provider.callTimeoutRemaining;
    final total = provider.callTimeoutSeconds;
    final progress = total > 0 ? (total - remaining) / total : 0.0;

    return Container(
      margin: const EdgeInsets.fromLTRB(14, 8, 14, 0),
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: Colors.red.shade50,
        borderRadius: BorderRadius.circular(10),
        border: Border.all(color: Colors.red.shade300),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              const SizedBox(
                width: 16,
                height: 16,
                child: CircularProgressIndicator(
                  strokeWidth: 2,
                  valueColor: AlwaysStoppedAnimation<Color>(Colors.red),
                ),
              ),
              const SizedBox(width: 10),
              Expanded(
                child: RichText(
                  text: TextSpan(
                    style: const TextStyle(fontSize: 13, color: Colors.black87),
                    children: [
                      const TextSpan(text: 'Sedang Memanggil: '),
                      TextSpan(
                        text:
                            '${activeItem.name.isNotEmpty ? activeItem.name : activeItem.phoneNumber} (${activeItem.phoneNumber})',
                        style: const TextStyle(fontWeight: FontWeight.bold),
                      ),
                    ],
                  ),
                ),
              ),
              Container(
                padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
                decoration: BoxDecoration(
                  color: Colors.red.shade600,
                  borderRadius: BorderRadius.circular(12),
                ),
                child: Text(
                  '${remaining}s',
                  style: const TextStyle(
                    color: Colors.white,
                    fontWeight: FontWeight.bold,
                    fontSize: 12,
                  ),
                ),
              ),
            ],
          ),
          const SizedBox(height: 6),
          ClipRRect(
            borderRadius: BorderRadius.circular(4),
            child: LinearProgressIndicator(
              value: progress.clamp(0.0, 1.0),
              backgroundColor: Colors.red.shade100,
              valueColor: AlwaysStoppedAnimation<Color>(Colors.red.shade600),
            ),
          ),
          const SizedBox(height: 8),
          Row(
            mainAxisAlignment: MainAxisAlignment.spaceBetween,
            children: [
              const Expanded(
                child: Text(
                  'Auto-hangup jika tidak diangkat/sibuk, lalu lanjut ke antrean',
                  style: TextStyle(fontSize: 11, color: Colors.black54),
                ),
              ),
              const SizedBox(width: 8),
              OutlinedButton.icon(
                style: OutlinedButton.styleFrom(
                  visualDensity: VisualDensity.compact,
                  foregroundColor: Colors.red.shade700,
                  side: BorderSide(color: Colors.red.shade400),
                  padding: const EdgeInsets.symmetric(
                    horizontal: 8,
                    vertical: 4,
                  ),
                ),
                icon: const Icon(Icons.call_end, size: 14),
                label: const Text(
                  'Tutup Sekarang',
                  style: TextStyle(fontSize: 11),
                ),
                onPressed: () => provider.hangupCurrentCall(),
              ),
            ],
          ),
        ],
      ),
    );
  }
}
