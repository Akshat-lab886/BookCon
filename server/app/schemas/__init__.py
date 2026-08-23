"""Pydantic schemas package."""

from app.schemas.auth import (
    DeviceInfoIn,
    DeviceOut,
    GoogleAuthIn,
    LoginIn,
    LogoutIn,
    RefreshIn,
    RegisterIn,
    TokensOut,
    UserOut,
    UserPatchIn,
)
from app.schemas.book import (
    BookBase,
    BookOut,
    BookPage,
    BookPatchIn,
    FileUrlOut,
    InitiateUploadIn,
    InitiateUploadOut,
)
from app.schemas.library import (
    AnnotationIn,
    AnnotationOut,
    AnnotationPatchIn,
    BookmarkIn,
    BookmarkOut,
    BookmarkPatchIn,
    LocatorModel,
    PositionIn,
    PositionOut,
    SeriesCreateIn,
    SeriesOut,
    SeriesPatchIn,
    ShelfCreateIn,
    ShelfOut,
    ShelfPatchIn,
    TagCreateIn,
    TagOut,
    TagPatchIn,
)
from app.schemas.sync import (
    StorageStats,
    SyncPullIn,
    SyncPullOut,
    SyncPushIn,
    SyncPushOut,
)

__all__ = [name for name in dir() if not name.startswith("_")]
