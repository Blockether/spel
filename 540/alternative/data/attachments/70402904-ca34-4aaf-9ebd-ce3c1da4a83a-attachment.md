## DELETE http://localhost:39953/echo → 200 OK

### Request Headers
```
DELETE http://localhost:39953/echo
```

### Response Headers
```
content-length: 34
content-type: application/json
date: Thu, 09 Apr 2026 19:02:00 GMT
```

### Response Body
```json
{
  "method": "DELETE",
  "path": "/echo"
}
```

### cURL
```bash
curl 'http://localhost:39953/echo' \
  -X DELETE
```
