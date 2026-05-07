import React, { useEffect, useRef, useCallback } from 'react';
import { View, Text, StyleSheet, TouchableOpacity, Animated } from 'react-native';
import { NativeStackScreenProps } from '@react-navigation/native-stack';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { Colors } from '../../constants/colors';
import { RootStackParamList } from '../../navigation/AppNavigator';

type Props = NativeStackScreenProps<RootStackParamList, 'Ready'>;

const COUNTDOWN_SECONDS = 300; // 5분

export const ReadyScreen = ({ navigation, route }: Props) => {
  const insets = useSafeAreaInsets();
  const { sequenceNumber, token, eventId, userId } = route.params;
  const [remaining, setRemaining] = React.useState(COUNTDOWN_SECONDS);
  const scaleAnim = useRef(new Animated.Value(0.8)).current;

  useEffect(() => {
    Animated.spring(scaleAnim, {
      toValue: 1,
      tension: 60,
      friction: 8,
      useNativeDriver: true,
    }).start();

    const timer = setInterval(() => {
      setRemaining(prev => {
        if (prev <= 1) {
          clearInterval(timer);
          return 0;
        }
        return prev - 1;
      });
    }, 1000);

    return () => clearInterval(timer);
  }, []);

  const handleGoToClaim = useCallback(() => {
    navigation.navigate('Claim', { token, eventId, userId });
  }, [navigation, token, eventId, userId]);

  const minutes = Math.floor(remaining / 60);
  const seconds = remaining % 60;
  const timeStr = `${String(minutes).padStart(2, '0')}:${String(seconds).padStart(2, '0')}`;

  return (
    <View style={[styles.container, { paddingTop: insets.top + 48, paddingBottom: insets.bottom + 24 }]}>
      <View style={styles.content}>
        <Animated.View style={[styles.badge, { transform: [{ scale: scaleAnim }] }]}>
          <Text style={styles.badgeNumber}>{sequenceNumber}</Text>
          <Text style={styles.badgeLabel}>번째 입장</Text>
        </Animated.View>

        <Text style={styles.title}>차례가 됐습니다!</Text>
        <Text style={styles.desc}>5분 안에 상품을 선택해주세요{'\n'}시간이 지나면 자동으로 취소됩니다</Text>

        <View style={styles.countdown}>
          <Text style={styles.countdownTime}>{timeStr}</Text>
          <Text style={styles.countdownLabel}>남은 시간</Text>
        </View>
      </View>

      <View style={styles.bottom}>
        <TouchableOpacity
          style={[styles.button, remaining === 0 && styles.buttonDisabled]}
          onPress={handleGoToClaim}
          disabled={remaining === 0}
          activeOpacity={0.85}
        >
          <Text style={styles.buttonText}>
            {remaining === 0 ? '시간이 만료되었습니다' : '상품 선택하러 가기'}
          </Text>
        </TouchableOpacity>
      </View>
    </View>
  );
};

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: Colors.cream,
    justifyContent: 'space-between',
    paddingHorizontal: 24,
  },
  content: {
    alignItems: 'center',
    gap: 16,
  },
  badge: {
    width: 100,
    height: 100,
    borderRadius: 50,
    backgroundColor: Colors.brandGreen,
    alignItems: 'center',
    justifyContent: 'center',
    marginBottom: 8,
    shadowColor: Colors.brandGreen,
    shadowOffset: { width: 0, height: 8 },
    shadowOpacity: 0.3,
    shadowRadius: 16,
    elevation: 8,
  },
  badgeNumber: {
    fontSize: 32,
    fontWeight: '700',
    color: Colors.white,
    lineHeight: 36,
  },
  badgeLabel: {
    fontSize: 11,
    fontWeight: '400',
    color: 'rgba(255,255,255,0.8)',
  },
  title: {
    fontSize: 24,
    fontWeight: '700',
    color: Colors.textPrimary,
  },
  desc: {
    fontSize: 13,
    fontWeight: '400',
    color: Colors.textMuted,
    textAlign: 'center',
    lineHeight: 20,
  },
  countdown: {
    marginTop: 24,
    padding: 24,
    backgroundColor: Colors.white,
    borderRadius: 16,
    alignItems: 'center',
    gap: 4,
    width: '100%',
    shadowColor: '#000',
    shadowOffset: { width: 0, height: 2 },
    shadowOpacity: 0.06,
    shadowRadius: 8,
    elevation: 3,
  },
  countdownTime: {
    fontSize: 48,
    fontWeight: '700',
    color: Colors.brandGreen,
    fontVariant: ['tabular-nums'],
  },
  countdownLabel: {
    fontSize: 12,
    color: Colors.textMuted,
  },
  bottom: {},
  button: {
    backgroundColor: Colors.brandGreen,
    height: 52,
    borderRadius: 12,
    alignItems: 'center',
    justifyContent: 'center',
  },
  buttonDisabled: {
    backgroundColor: Colors.cream2,
  },
  buttonText: {
    fontSize: 15,
    fontWeight: '700',
    color: Colors.white,
  },
});
