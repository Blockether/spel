## PUT http://localhost:38901/echo → 200 OK

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
date: Sat, 21 Nov 2026 18:36:21 GMT
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
curl 'http://localhost:38901/echo' \
  -X PUT \
  -d '{
  "name": "Eve Updated"
}'
```
