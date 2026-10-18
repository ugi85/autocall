enum CallStatus {
  pending,
  dialing,
  inCall,
  completed,
  failedOrBusy,
}

enum UploadStatus {
  idle,
  waitingRecording,
  uploading,
  uploaded,
  failed,
}

class CallItem {
  final String id;
  final String phoneNumber;
  final String name;
  final CallStatus status;
  final int durationSeconds;
  final DateTime? callTimestamp;
  final String? callType;
  final UploadStatus uploadStatus;
  final String? uploadMessage;
  final String? recordingFilePath;
  final int retryCount;

  CallItem({
    required this.id,
    required this.phoneNumber,
    this.name = '',
    this.status = CallStatus.pending,
    this.durationSeconds = 0,
    this.callTimestamp,
    this.callType,
    this.uploadStatus = UploadStatus.idle,
    this.uploadMessage,
    this.recordingFilePath,
    this.retryCount = 0,
  });

  String get durationFormatted {
    final minutes = (durationSeconds ~/ 60).toString().padLeft(2, '0');
    final seconds = (durationSeconds % 60).toString().padLeft(2, '0');
    return '$minutes:$seconds';
  }

  bool get isFinished =>
      status == CallStatus.completed || status == CallStatus.failedOrBusy;

  CallItem copyWith({
    String? id,
    String? phoneNumber,
    String? name,
    CallStatus? status,
    int? durationSeconds,
    DateTime? callTimestamp,
    String? callType,
    UploadStatus? uploadStatus,
    String? uploadMessage,
    String? recordingFilePath,
    int? retryCount,
  }) {
    return CallItem(
      id: id ?? this.id,
      phoneNumber: phoneNumber ?? this.phoneNumber,
      name: name ?? this.name,
      status: status ?? this.status,
      durationSeconds: durationSeconds ?? this.durationSeconds,
      callTimestamp: callTimestamp ?? this.callTimestamp,
      callType: callType ?? this.callType,
      uploadStatus: uploadStatus ?? this.uploadStatus,
      uploadMessage: uploadMessage ?? this.uploadMessage,
      recordingFilePath: recordingFilePath ?? this.recordingFilePath,
      retryCount: retryCount ?? this.retryCount,
    );
  }

  Map<String, dynamic> toMap() {
    return {
      'id': id,
      'phoneNumber': phoneNumber,
      'name': name,
      'status': status.name,
      'durationSeconds': durationSeconds,
      'callTimestamp': callTimestamp?.toIso8601String(),
      'callType': callType,
      'uploadStatus': uploadStatus.name,
      'uploadMessage': uploadMessage,
      'recordingFilePath': recordingFilePath,
      'retryCount': retryCount,
    };
  }

  factory CallItem.fromMap(Map<String, dynamic> map) {
    return CallItem(
      id: map['id'] as String? ?? DateTime.now().microsecondsSinceEpoch.toString(),
      phoneNumber: map['phoneNumber'] as String? ?? '',
      name: map['name'] as String? ?? '',
      status: CallStatus.values.firstWhere(
        (e) => e.name == map['status'],
        orElse: () => CallStatus.pending,
      ),
      durationSeconds: (map['durationSeconds'] as num?)?.toInt() ?? 0,
      callTimestamp: map['callTimestamp'] != null
          ? DateTime.tryParse(map['callTimestamp'] as String)
          : null,
      callType: map['callType'] as String?,
      uploadStatus: UploadStatus.values.firstWhere(
        (e) => e.name == map['uploadStatus'],
        orElse: () => UploadStatus.idle,
      ),
      uploadMessage: map['uploadMessage'] as String?,
      recordingFilePath: map['recordingFilePath'] as String?,
      retryCount: (map['retryCount'] as num?)?.toInt() ?? 0,
    );
  }
}
