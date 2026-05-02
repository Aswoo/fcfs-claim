import React, { useEffect, useRef } from 'react';
import { View, Animated, StyleSheet } from 'react-native';
import { Colors } from '../../constants/colors';

interface Props {
  stock: number;
  total: number;
}

export const StockBar = React.memo(({ stock, total }: Props) => {
  const ratio = total > 0 ? stock / total : 0;
  const fillAnim = useRef(new Animated.Value(0)).current;

  useEffect(() => {
    Animated.timing(fillAnim, {
      toValue: ratio,
      duration: 1200,
      useNativeDriver: false,
    }).start();
  }, [ratio]);

  const fillColor = ratio === 0 ? Colors.red : ratio <= 0.3 ? Colors.orange : Colors.brandGreenMid;

  return (
    <View style={styles.track}>
      <Animated.View
        style={[
          styles.fill,
          {
            backgroundColor: fillColor,
            width: fillAnim.interpolate({
              inputRange: [0, 1],
              outputRange: ['0%', '100%'],
            }),
          },
        ]}
      />
    </View>
  );
});

const styles = StyleSheet.create({
  track: {
    width: 160,
    height: 3,
    backgroundColor: Colors.cream2,
    borderRadius: 2,
    overflow: 'hidden',
  },
  fill: {
    height: 3,
    borderRadius: 2,
  },
});
