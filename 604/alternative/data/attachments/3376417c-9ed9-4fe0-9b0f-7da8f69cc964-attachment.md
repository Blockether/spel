## PATCH http://localhost:42375/echo → 200 OK

### Request Body
```json
{
  "email": "eve@new.com"
}
```

### Response Headers
```
content-length: 64
content-type: application/json
date: Tue, 14 Apr 2026 12:38:26 GMT
```

### Response Body
```json
{
  "method": "PATCH",
  "path": "/echo",
  "body": {
    "email": "eve@new.com"
  }
}
```

### cURL
```bash
curl 'http://localhost:42375/echo' \
  -X PATCH \
  -d '{
  "email": "eve@new.com"
}'
```
