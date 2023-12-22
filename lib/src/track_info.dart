class TrackInfo {
  final String? id;
  final String? title;
  final String? album;
  final String? artist;
  final String? source;
  final Duration? duration;
  final Duration? position;
  final int? state;
  final int? volumePercent;

  TrackInfo({
    required this.id,
    required this.title,
    required this.album,
    required this.artist,
    required this.source,
    required this.duration,
    required this.position,
    required this.state,
    required this.volumePercent,
  });

  factory TrackInfo.fromJson(Map<String, dynamic> json) {
    return TrackInfo(
      id: json['id'],
      title: json['title'],
      album: json['album'],
      artist: json['artist'],
      source: json['source'],
      duration: Duration(milliseconds: json['duration']),
      position: Duration(milliseconds: json['position']),
      state: json['state'],
      volumePercent: json['volumePercent'],
    );
  }

  Map<String, dynamic> toJson() {
    return {
      'id': id,
      'title': title,
      'album': album,
      'artist': artist,
      'source': source,
      'duration': duration?.inMilliseconds,
      'position': position?.inMilliseconds,
      'state': state,
      'volumePercent': volumePercent,
    };
  }
}
