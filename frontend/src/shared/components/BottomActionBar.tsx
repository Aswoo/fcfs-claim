import React from 'react';
import { View, Text, TouchableOpacity, StyleSheet } from 'react-native';
import { Colors } from '../../constants/colors';
import { Product } from '../../types';

interface Props {
  selectedProduct: Product | null;
  onClaim: () => void;
}

export const BottomActionBar = React.memo(({ selectedProduct, onClaim }: Props) => {
  const isActive = selectedProduct !== null;

  return (
    <View style={styles.container}>
      <Text style={styles.hint}>
        {isActive ? `${selectedProduct.name} 선택됨` : '상품을 선택해주세요'}
      </Text>
      <TouchableOpacity
        style={[styles.button, isActive ? styles.buttonActive : styles.buttonDisabled]}
        onPress={onClaim}
        disabled={!isActive}
        activeOpacity={0.9}
      >
        <Text style={[styles.buttonText, isActive ? styles.buttonTextActive : styles.buttonTextDisabled]}>
          선착순 신청하기
        </Text>
      </TouchableOpacity>
    </View>
  );
});

const styles = StyleSheet.create({
  container: {
    height: 80,
    backgroundColor: Colors.white,
    paddingTop: 10,
    paddingHorizontal: 14,
    paddingBottom: 16,
    gap: 7,
    shadowColor: '#000',
    shadowOffset: { width: 0, height: -4 },
    shadowOpacity: 0.08,
    shadowRadius: 24,
    elevation: 10,
  },
  hint: {
    fontSize: 11,
    fontWeight: '400',
    color: Colors.textMuted,
    textAlign: 'center',
  },
  button: {
    height: 44,
    borderRadius: 10,
    alignItems: 'center',
    justifyContent: 'center',
  },
  buttonActive: {
    backgroundColor: Colors.brandGreen,
  },
  buttonDisabled: {
    backgroundColor: Colors.cream2,
  },
  buttonText: {
    fontSize: 13,
    fontWeight: '700',
  },
  buttonTextActive: {
    color: Colors.white,
  },
  buttonTextDisabled: {
    color: Colors.textMuted,
  },
});
