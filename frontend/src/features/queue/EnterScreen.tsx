import React, { useCallback, useState } from 'react';
import { View, Text, StyleSheet, TouchableOpacity, ActivityIndicator, Alert } from 'react-native';
import { NativeStackScreenProps } from '@react-navigation/native-stack';
import { LinearGradient } from 'expo-linear-gradient';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { Colors } from '../../constants/colors';
import { RootStackParamList } from '../../navigation/AppNavigator';
import { queueService } from '../../services/queueService';

const EVENT_ID = 1;
const USER_ID = Math.floor(Math.random() * 900000) + 100000; // 임시 userId

type Props = NativeStackScreenProps<RootStackParamList, 'Enter'>;

export const EnterScreen = ({ navigation }: Props) => {
  const insets = useSafeAreaInsets();
  const [loading, setLoading] = useState(false);
  const [loadingWith20, setLoadingWith20] = useState(false);
  const [resetting, setResetting] = useState(false);

  const handleEnter = useCallback(async () => {
    setLoading(true);
    try {
      const { rank } = await queueService.enter(USER_ID, EVENT_ID);
      navigation.navigate('Waiting', { rank, eventId: EVENT_ID, userId: USER_ID });
    } catch (e) {
      Alert.alert('오류', '서버 연결에 실패했습니다.');
    } finally {
      setLoading(false);
    }
  }, [navigation]);

  const handleReset = useCallback(() => {
    Alert.alert(
      '테스트 초기화',
      '수령 기록, 대기열, 재고를 모두 초기화합니다.\n반복 테스트에만 사용하세요.',
      [
        { text: '취소', style: 'cancel' },
        {
          text: '초기화',
          style: 'destructive',
          onPress: async () => {
            setResetting(true);
            try {
              await queueService.reset();
              Alert.alert('완료', '초기화되었습니다.');
            } catch {
              Alert.alert('오류', '초기화에 실패했습니다.');
            } finally {
              setResetting(false);
            }
          },
        },
      ]
    );
  }, []);

  const handleEnterAfter20 = useCallback(async () => {
    setLoadingWith20(true);
    try {
      await queueService.simulatePrecedingUsers(20, EVENT_ID);
      const { rank } = await queueService.enter(USER_ID, EVENT_ID);
      navigation.navigate('Waiting', { rank, eventId: EVENT_ID, userId: USER_ID });
    } catch (e) {
      Alert.alert('오류', '서버 연결에 실패했습니다.');
    } finally {
      setLoadingWith20(false);
    }
  }, [navigation]);

  return (
    <LinearGradient
      colors={[Colors.brandGreenDark, '#2C5234']}
      style={[styles.container, { paddingTop: insets.top + 32, paddingBottom: insets.bottom + 24 }]}
    >
      <View style={styles.content}>
        <Text style={styles.label}>LIMITED EDITION</Text>
        <Text style={styles.title}>2025 프리퀀시{'\n'}리워드 지급</Text>
        <Text style={styles.desc}>선착순 한정 수량 · 지금 바로 대기열에 참여하세요</Text>

        <View style={styles.infoRow}>
          <InfoCard label="현재 대기" value="127명" />
          <InfoCard label="남은 수량" value="20개" />
          <InfoCard label="예상 대기" value="약 13분" />
        </View>
      </View>

      <View style={styles.bottom}>
        <TouchableOpacity
          style={styles.resetButton}
          onPress={handleReset}
          activeOpacity={0.7}
          disabled={resetting || loading || loadingWith20}
        >
          {resetting
            ? <ActivityIndicator size="small" color="rgba(255,255,255,0.4)" />
            : <Text style={styles.resetButtonText}>테스트 초기화</Text>
          }
        </TouchableOpacity>

        <TouchableOpacity
          style={styles.precedeButton}
          onPress={handleEnterAfter20}
          activeOpacity={0.75}
          disabled={loadingWith20 || loading}
        >
          {loadingWith20
            ? <ActivityIndicator color={Colors.goldLight} />
            : <Text style={styles.precedeButtonText}>내 앞에 20명 먼저 입장</Text>
          }
        </TouchableOpacity>
        <TouchableOpacity style={styles.enterButton} onPress={handleEnter} activeOpacity={0.85} disabled={loading || loadingWith20}>
          {loading
            ? <ActivityIndicator color={Colors.white} />
            : <Text style={styles.enterButtonText}>지금 입장하기</Text>
          }
        </TouchableOpacity>
      </View>
    </LinearGradient>
  );
};

const InfoCard = ({ label, value }: { label: string; value: string }) => (
  <View style={styles.infoCard}>
    <Text style={styles.infoValue}>{value}</Text>
    <Text style={styles.infoLabel}>{label}</Text>
  </View>
);

const styles = StyleSheet.create({
  container: {
    flex: 1,
    justifyContent: 'space-between',
    paddingHorizontal: 24,
  },
  content: {
    gap: 12,
  },
  label: {
    fontSize: 9,
    fontWeight: '700',
    letterSpacing: 2,
    color: Colors.goldLight,
  },
  title: {
    fontSize: 32,
    fontWeight: '400',
    color: '#F2F0EB',
    lineHeight: 40,
    marginTop: 4,
  },
  desc: {
    fontSize: 12,
    fontWeight: '400',
    color: Colors.headerSub,
    marginTop: 4,
  },
  infoRow: {
    flexDirection: 'row',
    gap: 12,
    marginTop: 32,
  },
  infoCard: {
    flex: 1,
    backgroundColor: 'rgba(255,255,255,0.1)',
    borderRadius: 12,
    padding: 14,
    alignItems: 'center',
    gap: 4,
  },
  infoValue: {
    fontSize: 16,
    fontWeight: '700',
    color: Colors.white,
  },
  infoLabel: {
    fontSize: 10,
    fontWeight: '400',
    color: Colors.headerSub,
  },
  bottom: {
    gap: 12,
  },
  resetButton: {
    alignSelf: 'center',
    paddingVertical: 6,
    paddingHorizontal: 14,
  },
  resetButtonText: {
    fontSize: 11,
    fontWeight: '400',
    color: 'rgba(255,255,255,0.35)',
    textDecorationLine: 'underline',
  },
  precedeButton: {
    height: 44,
    borderRadius: 12,
    borderWidth: 1,
    borderColor: 'rgba(255,255,255,0.3)',
    alignItems: 'center',
    justifyContent: 'center',
  },
  precedeButtonText: {
    fontSize: 13,
    fontWeight: '500',
    color: Colors.goldLight,
  },
  enterButton: {
    backgroundColor: Colors.brandGreen,
    height: 52,
    borderRadius: 12,
    alignItems: 'center',
    justifyContent: 'center',
  },
  enterButtonText: {
    fontSize: 15,
    fontWeight: '700',
    color: Colors.white,
  },
});
