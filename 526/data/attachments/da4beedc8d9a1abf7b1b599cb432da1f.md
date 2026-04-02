## PATCH http://localhost:35903/echo → 200 OK

### Request Headers
```
PATCH http://localhost:35903/echo
```

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
date: Thu, 02 Apr 2026 10:22:52 GMT
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
curl 'http://localhost:35903/echo' \
  -X PATCH \
  -d '{
  "email": "eve@new.com"
}'
```
