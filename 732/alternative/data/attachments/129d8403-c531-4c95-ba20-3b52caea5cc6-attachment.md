## DELETE http://localhost:40081/echo → 200 OK

### Response Headers
```
content-length: 34
content-type: application/json
date: Fri, 21 Aug 2026 03:01:48 GMT
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
curl 'http://localhost:40081/echo' \
  -X DELETE
```
