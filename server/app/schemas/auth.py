"""Pydantic DTOs — auth & account."""

from __future__ import annotations

from datetime import datetime

from pydantic import BaseModel, ConfigDict, EmailStr, Field


class DeviceOut(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: str
    name: str
    platform: str
    app_version: str
    last_seen_at: datetime | None = None
    revoked_at: datetime | None = None


class DeviceInfoIn(BaseModel):
    device_name: str = Field(min_length=1, max_length=200)
    app_version: str = "unknown"
    platform: str = "android"


class RegisterIn(DeviceInfoIn):
    email: EmailStr
    password: str = Field(min_length=8, max_length=128)
    display_name: str = ""


class LoginIn(DeviceInfoIn):
    email: EmailStr
    password: str = Field(min_length=1)


class GoogleAuthIn(DeviceInfoIn):
    id_token: str


class RefreshIn(BaseModel):
    refresh_token: str


class LogoutIn(BaseModel):
    refresh_token: str | None = None


class UserOut(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: str
    email: str
    display_name: str
    avatar_url: str | None = None
    created_at: datetime


class UserPatchIn(BaseModel):
    display_name: str | None = None
    avatar_url: str | None = None


class TokensOut(BaseModel):
    access_token: str
    refresh_token: str
    token_type: str = "bearer"
    expires_in: int
    user: UserOut
    device: DeviceOut
