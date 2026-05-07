import React, { useEffect, useRef } from 'react';
import { View, Text, StyleSheet, TouchableOpacity, Animated } from 'react-native';
import { NativeStackScreenProps } from '@react-navigation/native-stack';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { Colors } from '../../constants/colors';
import { RootStackParamList } from '../../navigation/AppNavigator';

type Props = NativeStackScreenProps<RootStackParamList, 'Success'>;

export const SuccessScreen = ({ navigation, route }: Props) => {
  const insets = useSafeAreaInsets();
  const { productName } = route.params;
  const scaleAnim = useRef(new Animated.Value(0)).current;
  const fadeAnim = useRef(new Animated.Value(0)).current;

  useEffect(() => {
    Animated.sequence([
      Animated.spring(scaleAnim, { toValue: 1, tension: 50, friction: 7, useNativeDriver: true }),
      Animated.timing(fadeAnim, { toValue: 1, duration: 400, useNativeDriver: true }),
    ]).start();
  }, []);

  return (
    <View style={[styles.container, { paddingTop: insets.top + 64, paddingBottom: insets.bottom + 24 }]}>
      <View style={styles.content}>
        <Animated.View style={[styles.icon, { transform: [{ scale: scaleAnim }] }]}>
          <Text style={styles.iconText}>✓</Text>
        </Animated.View>

        <Animated.View style={{ opacity: fadeAnim, alignItems: 'center', gap: 8 }}>
          <Text style={styles.title}>수령 완료!</Text>
          <Text style={styles.productName}>{productName}</Text>
          <Text style={styles.desc}>선착순 신청이 완료되었습니다.{'\n'}매장에서 수령해주세요.</Text>
        </Animated.View>
      </View>

      <TouchableOpacity
        style={styles.button}
        onPress={() => navigation.navigate('Enter')}
        activeOpacity={0.85}
      >
        <Text style={styles.buttonText}>처음으로</Text>
      </TouchableOpacity>
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
    gap: 32,
  },
  icon: {
    width: 100,
    height: 100,
    borderRadius: 50,
    backgroundColor: Colors.brandGreen,
    alignItems: 'center',
    justifyContent: 'center',
    shadowColor: Colors.brandGreen,
    shadowOffset: { width: 0, height: 8 },
    shadowOpacity: 0.35,
    shadowRadius: 16,
    elevation: 8,
  },
  iconText: {
    fontSize: 44,
    color: Colors.white,
    fontWeight: '700',
    lineHeight: 52,
  },
  title: {
    fontSize: 28,
    fontWeight: '700',
    color: Colors.textPrimary,
  },
  productName: {
    fontSize: 16,
    fontWeight: '600',
    color: Colors.brandGreen,
  },
  desc: {
    fontSize: 13,
    color: Colors.textMuted,
    textAlign: 'center',
    lineHeight: 20,
  },
  button: {
    backgroundColor: Colors.brandGreen,
    height: 52,
    borderRadius: 12,
    alignItems: 'center',
    justifyContent: 'center',
  },
  buttonText: {
    fontSize: 15,
    fontWeight: '700',
    color: Colors.white,
  },
});
