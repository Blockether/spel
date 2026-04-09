## DELETE http://localhost:44095/echo → 200 OK

### Request Headers
```
DELETE http://localhost:44095/echo
```

### Response Headers
```
content-length: 34
content-type: application/json
date: Thu, 09 Apr 2026 19:01:56 GMT
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
curl 'http://localhost:44095/echo' \
  -X DELETE
```
