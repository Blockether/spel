## PUT http://localhost:45959/echo → 200 OK

### Request Body
```json
{
  "name": "Eve Updated"
}
```

### Response Headers
```
content-length: 61
content-type: application/json
date: Thu, 27 Aug 2026 14:34:35 GMT
```

### Response Body
```json
{
  "method": "PUT",
  "path": "/echo",
  "body": {
    "name": "Eve Updated"
  }
}
```

### cURL
```bash
curl 'http://localhost:45959/echo' \
  -X PUT \
  -d '{
  "name": "Eve Updated"
}'
```
