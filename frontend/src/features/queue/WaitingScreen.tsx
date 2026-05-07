import React, { useEffect, useRef, useState } from 'react';
import { View, Text, StyleSheet, Animated } from 'react-native';
import { NativeStackScreenProps } from '@react-navigation/native-stack';
import EventSource from 'react-native-sse';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { Colors } from '../../constants/colors';
import { RootStackParamList } from '../../navigation/AppNavigator';
import { queueService } from '../../services/queueService';
import { BASE_URL } from '../../services/api';

type Props = NativeStackScreenProps<RootStackParamList, 'Waiting'>;

export const WaitingScreen = ({ navigation, route }: Props) => {
  const insets = useSafeAreaInsets();
  const { userId, eventId } = route.params;
  const [rank, setRank] = useState(route.params.rank);
  const progressAnim = useRef(new Animated.Value(0)).current;
  const dotAnim = useRef(new Animated.Value(0)).current;

  useEffect(() => {
    Animated.timing(progressAnim, {
      toValue: 0.35,
      duration: 1200,
      useNativeDriver: false,
    }).start();

    Animated.loop(
      Animated.sequence([
        Animated.timing(dotAnim, { toValue: 1, duration: 600, useNativeDriver: true }),
        Animated.timing(dotAnim, { toValue: 0.3, duration: 600, useNativeDriver: true }),
      ])
    ).start();
  }, []);

  // SSE로 ready 이벤트 수신, 폴링은 순위 표시용으로만 사용
  useEffect(() => {
    const es = new EventSource<'ready'>(
      `${BASE_URL}/api/v1/queue/subscribe?userId=${userId}&eventId=${eventId}`
    );

    es.addEventListener('ready', (e) => {
      const { token } = JSON.parse(e.data ?? '{}');
      navigation.replace('Ready', { token, sequenceNumber: rank, eventId, userId });
      es.close();
    });

    es.addEventListener('error', () => es.close());

    return () => es.close();
  }, [userId, eventId]);

  // 10초마다 순위 갱신 (화면 표시용)
  useEffect(() => {
    const poll = async () => {
      try {
        const status = await queueService.getStatus(userId, eventId);
        if (status.rank > 0) setRank(status.rank);
      } catch (e) {}
    };

    const interval = setInterval(poll, 10000);
    return () => clearInterval(interval);
  }, [userId, eventId]);

  return (
    <View style={styles.container}>
      <View style={[styles.header, { paddingTop: insets.top + 12 }]}>
        <Text style={styles.headerTitle}>대기 중</Text>
      </View>

      <View style={styles.content}>
        <Animated.Text style={[styles.rankLabel, { opacity: dotAnim }]}>●</Animated.Text>
        <Text style={styles.rankNumber}>{rank}번째</Text>
        <Text style={styles.rankDesc}>대기 중입니다</Text>

        <View style={styles.progressTrack}>
          <Animated.View
            style={[
              styles.progressFill,
              {
                width: progressAnim.interpolate({
                  inputRange: [0, 1],
                  outputRange: ['0%', '100%'],
                }),
              },
            ]}
          />
        </View>
        <Text style={styles.progressDesc}>앞에 {Math.max(0, rank - 1)}명 대기 중 · 초당 10명 처리</Text>

        <View style={styles.infoRow}>
          <View style={styles.infoItem}>
            <Text style={styles.infoValue}>약 {Math.ceil(rank / 10)}초</Text>
            <Text style={styles.infoLabel}>예상 대기</Text>
          </View>
          <View style={styles.divider} />
          <View style={styles.infoItem}>
            <Text style={styles.infoValue}>20개</Text>
            <Text style={styles.infoLabel}>남은 수량</Text>
          </View>
        </View>
      </View>
    </View>
  );
};

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: Colors.cream,
  },
  header: {
    backgroundColor: Colors.brandGreenDark,
    justifyContent: 'flex-end',
    paddingHorizontal: 16,
    paddingBottom: 12,
  },
  headerTitle: {
    fontSize: 16,
    fontWeight: '700',
    color: Colors.white,
  },
  content: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
    paddingHorizontal: 24,
    gap: 12,
  },
  rankLabel: {
    fontSize: 14,
    color: Colors.brandGreenMid,
  },
  rankNumber: {
    fontSize: 64,
    fontWeight: '700',
    color: Colors.textPrimary,
    lineHeight: 72,
  },
  rankDesc: {
    fontSize: 18,
    fontWeight: '400',
    color: Colors.textMuted,
    marginBottom: 24,
  },
  progressTrack: {
    width: '100%',
    height: 6,
    backgroundColor: Colors.cream2,
    borderRadius: 3,
    overflow: 'hidden',
  },
  progressFill: {
    height: 6,
    backgroundColor: Colors.brandGreenMid,
    borderRadius: 3,
  },
  progressDesc: {
    fontSize: 11,
    color: Colors.textMuted,
  },
  infoRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 24,
    marginTop: 16,
    padding: 20,
    backgroundColor: Colors.white,
    borderRadius: 16,
    width: '100%',
    shadowColor: '#000',
    shadowOffset: { width: 0, height: 2 },
    shadowOpacity: 0.06,
    shadowRadius: 8,
    elevation: 3,
  },
  infoItem: {
    flex: 1,
    alignItems: 'center',
    gap: 4,
  },
  infoValue: {
    fontSize: 18,
    fontWeight: '700',
    color: Colors.textPrimary,
  },
  infoLabel: {
    fontSize: 11,
    color: Colors.textMuted,
  },
  divider: {
    width: 1,
    height: 32,
    backgroundColor: Colors.cream2,
  },
});
